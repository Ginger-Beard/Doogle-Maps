package com.dooglemaps.validate;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.state.PatchSnapshot;
import com.dooglemaps.state.PatchStateStore;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.RuneLite;
import net.runelite.client.eventbus.Subscribe;

/**
 * Dumps the Geomancy interface so its contents can be decoded offline.
 *
 * <p>Geomancy shows the state of every farming patch in the game at once, which is by far the
 * cheapest way to fill in patches the player has not walked past. Reading it needs two things
 * we do not have yet: which widget carries the state, and which of <i>our</i> patches each
 * widget corresponds to.
 *
 * <p>The first is largely answered already. The interface is
 * {@link InterfaceID#FARMING_VIEW}, and RuneLite names all 329 of its components — three per
 * patch, a {@code _BACK}, a {@code _PIC} and a {@code _FRONT}. This probe resolves those names
 * by reflection so the dump is labelled rather than a wall of numbers.
 *
 * <p>The second is what the dump is really for. Alongside the widgets it writes out
 * <b>everything the plugin already knows</b> at that moment — every cached patch with its
 * region, varbit, crop and state. Casting Geomancy somewhere familiar therefore produces a
 * file with both halves of the mapping side by side, and matching them up becomes reading
 * rather than guessing.
 *
 * <p>Off by default: it is a development tool, not a feature. Nothing is sent anywhere.
 *
 * <p><b>Its job is done.</b> The last unknown — how a diseased patch is drawn — was captured on
 * 2026-08-05 and the whole rendering is decoded in {@code NOTES.md}. Kept rather than deleted
 * because the same probe is what would confirm a rendering change after a game update, and
 * rebuilding it from scratch to answer one question would be worse than leaving it switched off.
 */
@Slf4j
@Singleton
public class GeomancyProbe
{
	/** Component names, resolved from RuneLite's own constants so the dump is readable. */
	private static final Map<Integer, String> COMPONENT_NAMES = componentNames();

	/**
	 * Text the interface shows in place of a patch until the server fills it in.
	 *
	 * <p>The whole interface arrives empty and populates a moment later, so a dump taken when
	 * it opens catches nothing but this.
	 */
	private static final String PLACEHOLDER = "Loading...";

	/** Snapshots per cast, enough to click through all six tabs with room to spare. */
	private static final int MAX_SNAPSHOTS = 12;

	private final Client client;
	private final DoogleMapsConfig config;
	private final PatchStateStore patches;

	/**
	 * Bar fills and tooltip phrasings already seen, across every cast ever.
	 *
	 * <p>The interface only shows what your patches happen to be doing. A diseased patch may
	 * well draw differently from a dead one, but there is no way to arrange for one on demand
	 * — so instead of asking for a capture at the right moment, the probe remembers what it
	 * has seen and says so the first time something new turns up.
	 */
	private final Set<String> vocabulary = new HashSet<>();
	private boolean vocabularyLoaded;

	private File target;
	private int snapshots;
	private int lastFingerprint;

	@Inject
	private GeomancyProbe(Client client, DoogleMapsConfig config, PatchStateStore patches)
	{
		this.client = client;
		this.config = config;
		this.patches = patches;
	}

	/**
	 * Starts a capture when the interface opens.
	 *
	 * <p>Deliberately does not dump here. At this point every patch widget is hidden and reads
	 * "Loading..." — the interface is a shell that the server fills in over the following
	 * ticks, so anything written now is empty.
	 */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() != InterfaceID.FARMING_VIEW || !config.probeGeomancy())
		{
			return;
		}

		target = new File(new File(RuneLite.RUNELITE_DIR, "doogle-maps"),
			"geomancy-" + Instant.now().getEpochSecond() + ".tsv");
		snapshots = 0;
		lastFingerprint = 0;
		loadVocabulary();

		// The patch cache cannot change while the interface is up, so it is written once and
		// every snapshot below is read against it.
		writeHeader();
	}

	/**
	 * Takes a snapshot each time the interface's contents settle on something new.
	 *
	 * <p>Repeating rather than dumping once, because the six tabs are populated as you open
	 * them: one cast plus a click through the tabs produces one file covering all of them.
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (target == null)
		{
			return;
		}

		// Checked here as well as on the way in, so switching the probe off stops it now rather
		// than at the end of whatever cast is in flight. It is a development aid and the thing
		// someone wants when they turn it off is silence, not silence shortly.
		if (!config.probeGeomancy())
		{
			target = null;
			return;
		}

		Widget root = client.getWidget(InterfaceID.FARMING_VIEW, 0);
		if (root == null)
		{
			// Interface closed; stop until it is cast again.
			log.info("Geomancy capture finished: {} snapshot(s) in {}", snapshots, target);
			target = null;
			return;
		}

		List<String> rows = collect(root);
		if (rows.isEmpty() || stillLoading(rows))
		{
			return;
		}

		int fingerprint = rows.hashCode();
		if (fingerprint == lastFingerprint)
		{
			return;
		}
		lastFingerprint = fingerprint;

		if (snapshots >= MAX_SNAPSHOTS)
		{
			return;
		}

		snapshots++;
		append(rows);
	}

	private static boolean stillLoading(List<String> rows)
	{
		for (String row : rows)
		{
			if (row.contains(PLACEHOLDER))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Reads {@link InterfaceID.FarmingView}'s constants into an id-to-name map.
	 *
	 * <p>Enumerated rather than transcribed: there are 329 of them, they are already correct in
	 * the API, and a copied list would be both tedious and stale within a release.
	 *
	 * <p>Through a class literal rather than {@code Class.forName}. A string lookup would fail
	 * silently if RuneLite ever moved the class — the dump would still be written, just as an
	 * unlabelled wall of numbers — and it is the kind of reflection that draws attention in a
	 * Plugin Hub review for no benefit. This way the compiler checks it.
	 */
	private static Map<Integer, String> componentNames()
	{
		Map<Integer, String> names = new HashMap<>();
		for (Field field : InterfaceID.FarmingView.class.getFields())
		{
			if (field.getType() != int.class)
			{
				continue;
			}
			try
			{
				names.put(field.getInt(null), field.getName());
			}
			catch (IllegalAccessException e)
			{
				// A public static final int on a public class; unreachable in practice.
				log.debug("Could not read {}", field.getName(), e);
			}
		}
		return names;
	}

	/** A component's name from RuneLite's constants, or empty if it has none. */
	static String nameOf(int componentId)
	{
		return COMPONENT_NAMES.getOrDefault(componentId, "");
	}

	/** How many component names were resolved. Zero means the reflection has gone stale. */
	static int knownComponentCount()
	{
		return COMPONENT_NAMES.size();
	}

	/** Every component name the interface defines. */
	static java.util.Collection<String> knownComponentNames()
	{
		return java.util.Collections.unmodifiableCollection(COMPONENT_NAMES.values());
	}

	/** Writes the file's preamble and the patch cache, once per cast. */
	private void writeHeader()
	{
		try
		{
			File dir = target.getParentFile();
			if (!dir.exists() && !dir.mkdirs())
			{
				target = null;
				return;
			}

			try (Writer writer = Files.newBufferedWriter(target.toPath(), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
				PrintWriter out = new PrintWriter(writer))
			{
				out.println("# Geomancy interface dump, " + Instant.now());
				out.println("# Snapshots of the interface follow the patch list. Each is taken");
				out.println("# once the contents settle on something new, so clicking through the");
				out.println("# six tabs adds one snapshot per tab.");
				out.println();
				out.println("## patches we already know");
				out.println("key\tregion\tvarbit\tpatch\ttype\tproduce\tstate\tstage\tcompost\tlastSeen");
				writeKnownPatches(out);
			}
		}
		catch (IOException e)
		{
			log.warn("Could not start {}", target, e);
			target = null;
		}
	}

	/** Appends one snapshot of the interface. */
	private void append(List<String> rows)
	{
		try (Writer writer = Files.newBufferedWriter(target.toPath(), StandardCharsets.UTF_8,
			StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			PrintWriter out = new PrintWriter(writer))
		{
			out.println();
			out.println("## snapshot " + snapshots);
			out.println("id\tname\tchild\thidden\tsprite\titem\tqty\tmodel\tcolour\tw\th\tow\toh\ttext\tactions");
			rows.forEach(out::println);
		}
		catch (IOException e)
		{
			log.warn("Could not append to {}", target, e);
			target = null;
		}
	}

	/**
	 * Walks the whole widget tree and returns the rows worth recording.
	 *
	 * <p>Named components are the ones expected to matter, but the state could just as easily
	 * live on a dynamic child with no constant at all, and a dump that quietly omitted it
	 * would send the next reader in circles.
	 */
	private List<String> collect(Widget root)
	{
		List<String> rows = new ArrayList<>();
		Deque<Widget> queue = new ArrayDeque<>();
		// Keyed on component and child index: the same widget is reachable through several of
		// the child accessors, and identity alone let every row through twice.
		Set<Long> seen = new HashSet<>();
		queue.add(root);

		while (!queue.isEmpty())
		{
			Widget widget = queue.poll();
			if (widget == null || !seen.add(((long) widget.getId() << 32) ^ (widget.getIndex() & 0xFFFFFFFFL)))
			{
				continue;
			}

			String name = nameOf(widget.getId());
			if (name.endsWith("_BACK") && widget.getIndex() == 0)
			{
				// Child 0 is the bar's fill: sprite 1040 while the crop is alive, a flat red
				// rectangle once it is dead. Whatever a diseased patch does, it shows up here.
				noteVocabulary("bar", String.format("sprite=%d colour=%06X",
					widget.getSpriteId(), widget.getTextColor()));
			}
			else if ("TOOLTIP".equals(name) && widget.getText() != null && !widget.getText().isEmpty())
			{
				noteTooltip(widget.getText());
			}

			if (carriesInformation(widget))
			{
				rows.add(String.format("%d\t%s\t%d\t%b\t%d\t%d\t%d\t%d\t%06X\t%d\t%d\t%d\t%d\t%s\t%s",
					widget.getId(),
					name,
					widget.getIndex(),
					widget.isHidden(),
					widget.getSpriteId(),
					widget.getItemId(),
					widget.getItemQuantity(),
					widget.getModelId(),
					widget.getTextColor(),
					// Geometry, because each patch is drawn with a progress bar and it is the
					// bar's *width* that carries how far along the crop is - the same trick
					// this plugin's own rows use. Colour distinguishes a dead patch (red);
					// width distinguishes everything else.
					widget.getWidth(),
					widget.getHeight(),
					widget.getOriginalWidth(),
					widget.getOriginalHeight(),
					clean(widget.getText()),
					actions(widget)));
			}

			addAll(queue, widget.getStaticChildren());
			addAll(queue, widget.getDynamicChildren());
			addAll(queue, widget.getNestedChildren());
			addAll(queue, widget.getChildren());
		}
		return rows;
	}

	/**
	 * Notes anything the dumps have not shown before, and says so out loud.
	 *
	 * <p>Two things are worth watching: the colour of a patch's progress-bar fill, which is how
	 * a dead patch is marked, and the clauses a tooltip is built from. A diseased patch should
	 * announce itself through one or both.
	 */
	private void noteVocabulary(String kind, String value)
	{
		if (value == null || value.isEmpty() || !vocabulary.add(kind + '\t' + value))
		{
			return;
		}

		log.info("Geomancy: new {} seen - {}", kind, value);
		File file = new File(new File(RuneLite.RUNELITE_DIR, "doogle-maps"),
			"geomancy-vocabulary.tsv");
		try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8,
			StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			PrintWriter out = new PrintWriter(writer))
		{
			out.println(kind + '\t' + value);
		}
		catch (IOException e)
		{
			log.debug("Could not record vocabulary", e);
		}
	}

	/** Reads back what previous sessions already saw, so only genuinely new things are logged. */
	private void loadVocabulary()
	{
		if (vocabularyLoaded)
		{
			return;
		}
		vocabularyLoaded = true;

		File file = new File(new File(RuneLite.RUNELITE_DIR, "doogle-maps"),
			"geomancy-vocabulary.tsv");
		if (!file.exists())
		{
			return;
		}

		try
		{
			vocabulary.addAll(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			log.debug("Could not read vocabulary", e);
		}
	}

	/**
	 * Splits a tooltip into its clauses, with the stage numbers generalised.
	 *
	 * <p>Otherwise every patch at a different stage would read as new and drown the genuinely
	 * novel phrasing that is being watched for.
	 */
	private void noteTooltip(String text)
	{
		for (String clause : text.split("<br>"))
		{
			noteVocabulary("tooltip", clause.trim().replaceAll("\\d+ / \\d+", "n / m"));
		}
	}

	/** Whether a widget says anything at all, as opposed to being layout. */
	private static boolean carriesInformation(Widget widget)
	{
		return widget.getSpriteId() > 0
			|| widget.getItemId() > 0
			|| widget.getModelId() > 0
			|| widget.getTextColor() != 0
			|| widget.getWidth() > 0
			|| (widget.getText() != null && !widget.getText().isEmpty())
			|| COMPONENT_NAMES.containsKey(widget.getId());
	}

	/**
	 * Everything the plugin currently believes, for correlating against the widgets.
	 *
	 * <p>This is the half that makes the dump decodable. Cast Geomancy somewhere with patches
	 * in known, ideally <i>different</i> states, and the correspondence falls out.
	 */
	private void writeKnownPatches(PrintWriter out)
	{
		for (FarmPatch patch : FarmingWorldData.getAllPatches())
		{
			PatchSnapshot snapshot = patches.get(patch);
			if (snapshot == null)
			{
				continue;
			}

			out.printf("%s\t%s\t%d\t%s\t%s\t%s\t%s\t%d\t%s\t%d%n",
				patch.getKey(),
				patch.getKey().contains(".") ? patch.getKey().split("\\.")[0] : "",
				patch.getVarbit(),
				clean(patch.getDisplayName()),
				patch.getType().name(),
				snapshot.getProduce() == null ? "" : snapshot.getProduce().name(),
				snapshot.getCropState() == null ? "" : snapshot.getCropState().name(),
				snapshot.getStage(),
				snapshot.getCompost() == null ? "" : snapshot.getCompost().name(),
				snapshot.getLastSeen());
		}
	}

	private static void addAll(Deque<Widget> queue, Widget[] children)
	{
		if (children != null)
		{
			for (Widget child : children)
			{
				queue.add(child);
			}
		}
	}

	private static String actions(Widget widget)
	{
		String[] actions = widget.getActions();
		if (actions == null)
		{
			return "";
		}

		StringBuilder text = new StringBuilder();
		for (String action : actions)
		{
			if (action != null && !action.isEmpty())
			{
				if (text.length() > 0)
				{
					text.append('|');
				}
				text.append(action);
			}
		}
		return text.toString();
	}

	/** Strips tabs and newlines so a value cannot break the column it sits in. */
	private static String clean(String text)
	{
		return text == null ? "" : text.replaceAll("[\t\r\n]+", " ").trim();
	}
}
