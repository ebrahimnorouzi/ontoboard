package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.canvas.mxGraphics2DCanvas;
import com.mxgraph.shape.mxDefaultTextShape;
import com.mxgraph.util.mxConstants;
import com.mxgraph.util.mxUtils;
import com.mxgraph.view.mxCellState;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.font.TextAttribute;
import java.util.HashMap;
import java.util.Map;
import javax.swing.text.StyleContext;

/**
 * The board's label painter: JGraphX's own, except that a symbol the board's family lacks is drawn
 * from a font that has it (1.135.1, Windows study rehearsal).
 *
 * <p>On Windows the family is Segoe UI, and JGraphX makes a label's font with
 * {@code new Font(family, ...)}: a physical font. Java gives only its logical fonts (Dialog,
 * SansSerif, ...) a fallback per character there, so every symbol Segoe UI has no glyph for came
 * out as its missing-glyph box: the {@code ∃} that starts every relation's label, {@code ∀},
 * {@code ⊔}, {@code ⊓}, {@code ∧} and {@code ▸}. Participants read "□ has specified input". macOS
 * was spared because its text system falls back by itself.
 *
 * <p>{@link StyleContext#getFont} returns the same family with the logical Dialog font's fallback
 * chain behind it - on Windows that chain holds Segoe UI Symbol, Segoe UI's own symbol face. The
 * family stays the first font and supplies every metric, so Latin text, line heights, box sizes,
 * wrapping and routes stay exactly as they were measured: only the symbols change, from a box to
 * the symbol. On macOS the JDK returns the font as it is.
 *
 * <p>{@link #paintShape} is JGraphX 4.2.2's {@code mxDefaultTextShape.paintShape} (BSD licence),
 * read from its bytecode, with one line changed: the font comes from {@link #fontFor} where
 * JGraphX calls {@code mxUtils.getFont(style, scale)}. Measuring is untouched: SchemaGraph and
 * JGraphX size boxes and label chips with the family alone, which is what they did before.
 */
final class FallbackTextShape extends mxDefaultTextShape {

    /**
     * A private context, because {@link StyleContext#getFont} reuses one search key per context,
     * and an export can paint off the event thread while the board paints on it.
     */
    private static final StyleContext FONTS = new StyleContext();

    /** The font JGraphX would draw this style's label in, with a fallback for missing glyphs. */
    static Font fontFor(Map<String, Object> style, double scale) {
        String family = mxUtils.getString(style, mxConstants.STYLE_FONTFAMILY,
                mxConstants.DEFAULT_FONTFAMILY);
        int size = mxUtils.getInt(style, mxConstants.STYLE_FONTSIZE, mxConstants.DEFAULT_FONTSIZE);
        int bits = mxUtils.getInt(style, mxConstants.STYLE_FONTSTYLE);
        int awtStyle = ((bits & mxConstants.FONT_BOLD) == mxConstants.FONT_BOLD ? Font.BOLD : Font.PLAIN)
                + ((bits & mxConstants.FONT_ITALIC) == mxConstants.FONT_ITALIC ? Font.ITALIC : Font.PLAIN);
        Font font;
        synchronized (FONTS) {
            font = FONTS.getFont(family, awtStyle, (int) (size * scale));
        }
        Map<TextAttribute, Object> extras = new HashMap<TextAttribute, Object>();
        if ((bits & mxConstants.FONT_UNDERLINE) == mxConstants.FONT_UNDERLINE) {
            extras.put(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
        }
        if ((bits & mxConstants.FONT_STRIKETHROUGH) == mxConstants.FONT_STRIKETHROUGH) {
            extras.put(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON);
        }
        // A font derived from the fallback font keeps its fallback.
        return font.deriveFont(extras);
    }

    @Override
    public void paintShape(mxGraphics2DCanvas canvas, String text, mxCellState state,
            Map<String, Object> style) {
        Rectangle rect = state.getLabelBounds().getRectangle();
        Graphics2D g = canvas.getGraphics();
        if (g.getClipBounds() != null && !g.getClipBounds().intersects(rect)) {
            return;
        }
        boolean horizontal = mxUtils.isTrue(style, mxConstants.STYLE_HORIZONTAL, true);
        double scale = canvas.getScale();
        int x = rect.x;
        int y = rect.y;
        int w = rect.width;
        int h = rect.height;
        if (!horizontal) {
            g.rotate(-Math.PI / 2, x + w / 2, y + h / 2);
            g.translate(w / 2 - h / 2, h / 2 - w / 2);
        }
        g.setColor(mxUtils.getColor(style, mxConstants.STYLE_FONTCOLOR, Color.black));
        // The one line that differs from JGraphX: mxUtils.getFont(style, scale) there.
        Font scaledFont = fontFor(style, scale);
        g.setFont(scaledFont);
        int fontSize = mxUtils.getInt(style, mxConstants.STYLE_FONTSIZE, mxConstants.DEFAULT_FONTSIZE);
        FontMetrics fm = g.getFontMetrics();
        double fontScaling = (double) scaledFont.getSize() / (double) fontSize;
        double fontScaleRatio = fontScaling / scale;
        y += 2 * fm.getMaxAscent() - fm.getHeight() + mxConstants.LABEL_INSET * scale;
        String vertAlign = mxUtils.getString(style, mxConstants.STYLE_VERTICAL_ALIGN,
                mxConstants.ALIGN_MIDDLE);
        double vertAlignProportion = 0.5;
        if (vertAlign.equals(mxConstants.ALIGN_TOP)) {
            vertAlignProportion = 0;
        } else if (vertAlign.equals(mxConstants.ALIGN_BOTTOM)) {
            vertAlignProportion = 1.0;
        }
        y += (1.0 - fontScaleRatio) * h * vertAlignProportion;
        String align = mxUtils.getString(style, mxConstants.STYLE_ALIGN, mxConstants.ALIGN_CENTER);
        if (align.equals(mxConstants.ALIGN_LEFT)) {
            x += mxConstants.LABEL_INSET * scale;
        } else if (align.equals(mxConstants.ALIGN_RIGHT)) {
            x -= mxConstants.LABEL_INSET * scale;
        }
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            int dx = 0;
            if (align.equals(mxConstants.ALIGN_CENTER)) {
                int sw = fm.stringWidth(lines[i]);
                dx = horizontal ? (w - sw) / 2 : (h - sw) / 2;
            } else if (align.equals(mxConstants.ALIGN_RIGHT)) {
                int sw = fm.stringWidth(lines[i]);
                dx = (horizontal ? w : h) - sw;
            }
            g.drawString(lines[i], x + dx, y);
            postProcessLine(text, lines[i], fm, canvas, x + dx, y);
            y += fm.getHeight() + mxConstants.LINESPACING;
        }
    }
}
