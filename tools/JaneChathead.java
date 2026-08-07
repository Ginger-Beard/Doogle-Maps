import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Turns an in-game screenshot of a chathead into a bundled icon.
 *
 * <p>Run with {@code java tools/JaneChathead.java <in.png> <out.png> <width> <height>}.
 *
 * <p>Exists because the wiki's Guildmaster Jane chathead renders without visible eyes, and at the
 * 18-20px this is actually drawn at, eyes are most of what makes a face recognisable. The
 * screenshot is the authoritative look.
 *
 * <h2>Flood fill rather than a brightness threshold</h2>
 *
 * The chat backdrop is near-black, and so are her eyes. Anything that removed dark pixels globally
 * would punch two holes straight through the feature this is being done for. Filling inward from
 * the border only removes background that is <i>connected</i> to the edge, so interior darks
 * survive by construction.
 */
public final class JaneChathead
{
	/** How far a pixel may sit from the sampled backdrop and still count as background. */
	private static final int TOLERANCE = 40;

	public static void main(String[] args) throws Exception
	{
		BufferedImage src = ImageIO.read(new File(args[0]));
		int outW = Integer.parseInt(args[2]);
		int outH = Integer.parseInt(args[3]);

		BufferedImage cut = new BufferedImage(src.getWidth(), src.getHeight(),
			BufferedImage.TYPE_INT_ARGB);
		cut.getGraphics().drawImage(src, 0, 0, null);

		// The backdrop is sampled from the corners rather than assumed, so this works on any
		// screenshot rather than only on the one it was written for.
		int backdrop = average(src, new int[][]{{0, 0}, {src.getWidth() - 1, 0},
			{0, src.getHeight() - 1}, {src.getWidth() - 1, src.getHeight() - 1}});

		floodFromBorder(cut, backdrop);
		BufferedImage cropped = cropToContent(cut);

		BufferedImage out = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
			RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

		// Fitted rather than stretched, and centred, so the proportions survive the resize.
		double scale = Math.min(outW / (double) cropped.getWidth(),
			outH / (double) cropped.getHeight());
		int w = (int) Math.round(cropped.getWidth() * scale);
		int h = (int) Math.round(cropped.getHeight() * scale);
		g.drawImage(cropped, (outW - w) / 2, (outH - h) / 2, w, h, null);
		g.dispose();

		ImageIO.write(out, "png", new File(args[1]));
		System.out.println("wrote " + args[1] + " " + outW + "x" + outH
			+ " (cropped source " + cropped.getWidth() + "x" + cropped.getHeight() + ")");
	}

	private static void floodFromBorder(BufferedImage img, int backdrop)
	{
		int w = img.getWidth();
		int h = img.getHeight();
		boolean[] seen = new boolean[w * h];
		Deque<int[]> queue = new ArrayDeque<>();

		for (int x = 0; x < w; x++)
		{
			queue.add(new int[]{x, 0});
			queue.add(new int[]{x, h - 1});
		}
		for (int y = 0; y < h; y++)
		{
			queue.add(new int[]{0, y});
			queue.add(new int[]{w - 1, y});
		}

		while (!queue.isEmpty())
		{
			int[] p = queue.poll();
			int x = p[0];
			int y = p[1];
			if (x < 0 || y < 0 || x >= w || y >= h || seen[y * w + x])
			{
				continue;
			}
			seen[y * w + x] = true;

			if (distance(img.getRGB(x, y), backdrop) > TOLERANCE)
			{
				continue;
			}
			img.setRGB(x, y, 0);
			queue.add(new int[]{x + 1, y});
			queue.add(new int[]{x - 1, y});
			queue.add(new int[]{x, y + 1});
			queue.add(new int[]{x, y - 1});
		}
	}

	private static BufferedImage cropToContent(BufferedImage img)
	{
		int minX = img.getWidth();
		int minY = img.getHeight();
		int maxX = -1;
		int maxY = -1;
		for (int y = 0; y < img.getHeight(); y++)
		{
			for (int x = 0; x < img.getWidth(); x++)
			{
				if (((img.getRGB(x, y) >>> 24) & 0xFF) > 8)
				{
					minX = Math.min(minX, x);
					minY = Math.min(minY, y);
					maxX = Math.max(maxX, x);
					maxY = Math.max(maxY, y);
				}
			}
		}
		return img.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
	}

	private static int average(BufferedImage img, int[][] points)
	{
		long r = 0;
		long g = 0;
		long b = 0;
		for (int[] p : points)
		{
			int c = img.getRGB(p[0], p[1]);
			r += (c >> 16) & 0xFF;
			g += (c >> 8) & 0xFF;
			b += c & 0xFF;
		}
		int n = points.length;
		return (int) ((r / n) << 16 | (g / n) << 8 | (b / n));
	}

	private static int distance(int a, int b)
	{
		int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
		int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
		int db = (a & 0xFF) - (b & 0xFF);
		return (int) Math.sqrt(dr * dr + dg * dg + db * db);
	}
}
