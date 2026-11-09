package de.fizkarlsruhe.ise.ontoboard.assistant.chat;

/** A context chip as it is sent: its title and the text it stood for at the time of sending. */
public final class RenderedItem {

    private final String title;
    private final String text;

    public RenderedItem(String title, String text) {
        this.title = title == null ? "" : title;
        this.text = text == null ? "" : text;
    }

    public String getTitle() {
        return title;
    }

    public String getText() {
        return text;
    }
}
