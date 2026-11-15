package de.fizkarlsruhe.ise.ontoboard.assistant.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.UIManager;

/**
 * One message: the person's on the right, tinted with the look-and-feel's selection colour; the
 * assistant's on the left, a shade off the background. A small line above names who wrote it
 * and, once an answer is finished, its time and tokens. The text can be selected and copied.
 *
 * <p>Colours come from the look-and-feel, so Protégé's themes, dark ones included, still work.
 */
final class Bubble extends JPanel implements StackLayout.Measured {

    private static final long serialVersionUID = 1L;
    private static final int PAD = 8;
    private static final int ARC = 14;
    private static final int MARGIN = 2;

    private final boolean mine;
    private final JLabel meta = new JLabel(" ");
    private final JTextArea text = new JTextArea();
    private int measuredWidth = -1;
    private int measuredHeight;

    Bubble(boolean mine, String metaText, String body) {
        super(null);
        this.mine = mine;
        setOpaque(false);
        meta.setText(metaText);
        Font base = text.getFont();
        Font label = UIManager.getFont("Label.font");
        if (label != null) {
            base = label;
        }
        // Plain whatever the look-and-feel's labels are (Metal's are bold): this is reading text.
        base = base.deriveFont(Font.PLAIN);
        meta.setFont(base.deriveFont(base.getSize2D() - 1f));
        meta.setForeground(muted());
        text.setFont(base);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setBorder(BorderFactory.createEmptyBorder());
        Color foreground = UIManager.getColor("Label.foreground");
        if (foreground != null) {
            text.setForeground(foreground);
        }
        text.setText(body);
        add(meta);
        add(text);
    }

    boolean isMine() {
        return mine;
    }

    String getText() {
        return text.getText();
    }

    void setText(String body) {
        if (!body.equals(text.getText())) {
            text.setText(body);
            measuredWidth = -1;
        }
    }

    void setMeta(String words) {
        meta.setText(words);
        measuredWidth = -1;
    }

    /** The box: as wide as its longest line, up to most of the column. */
    private int boxWidth(int width) {
        int most = (int) (width * (mine ? 0.85 : 0.96)) - 2 * MARGIN;
        FontMetrics metrics = text.getFontMetrics(text.getFont());
        int longest = meta.getPreferredSize().width;
        for (String line : text.getText().split("\n", -1)) {
            longest = Math.max(longest, metrics.stringWidth(line));
        }
        return Math.max(40, Math.min(most, longest + 2 * PAD + 4));
    }

    @Override
    public int heightAt(int width) {
        if (width == measuredWidth) {
            return measuredHeight;
        }
        int inner = boxWidth(width) - 2 * PAD;
        text.setSize(inner, Math.max(text.getHeight(), 1));
        int textHeight = text.getPreferredSize().height;
        measuredWidth = width;
        measuredHeight = meta.getPreferredSize().height + textHeight + 2 * PAD + 2 * MARGIN + 2;
        return measuredHeight;
    }

    @Override
    public void doLayout() {
        int box = boxWidth(getWidth());
        int x = mine ? getWidth() - box - MARGIN : MARGIN;
        int metaHeight = meta.getPreferredSize().height;
        meta.setBounds(x + PAD, MARGIN + PAD - 2, box - 2 * PAD, metaHeight);
        text.setBounds(x + PAD, MARGIN + PAD + metaHeight, box - 2 * PAD,
                getHeight() - metaHeight - 2 * PAD - 2 * MARGIN);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int box = boxWidth(getWidth());
            int x = mine ? getWidth() - box - MARGIN : MARGIN;
            g.setColor(mine ? mineColor() : theirsColor());
            g.fillRoundRect(x, MARGIN, box, getHeight() - 2 * MARGIN, ARC, ARC);
        } finally {
            g.dispose();
        }
    }

    static Color background() {
        Color color = UIManager.getColor("Panel.background");
        return color == null ? Color.WHITE : color;
    }

    static boolean dark() {
        Color bg = background();
        return (0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue()) < 128;
    }

    static Color muted() {
        return dark() ? new Color(0xa0, 0xa0, 0xa0) : new Color(0x70, 0x70, 0x70);
    }

    private static Color mineColor() {
        Color accent = UIManager.getColor("List.selectionBackground");
        if (accent == null) {
            accent = new Color(0x2f, 0x6f, 0xd0);
        }
        return blend(accent, background(), 0.22);
    }

    private static Color theirsColor() {
        Color bg = background();
        return dark() ? blend(Color.WHITE, bg, 0.07) : blend(Color.BLACK, bg, 0.05);
    }

    static Color blend(Color top, Color under, double share) {
        return new Color((int) (top.getRed() * share + under.getRed() * (1 - share)),
                (int) (top.getGreen() * share + under.getGreen() * (1 - share)),
                (int) (top.getBlue() * share + under.getBlue() * (1 - share)));
    }
}
