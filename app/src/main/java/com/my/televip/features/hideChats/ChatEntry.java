package com.my.televip.features.hideChats;

/**
 * One row of the chat picker. {@link #id} uses the same key as the hide rules (user id, -chat id).
 */
public class ChatEntry {

    public final long id;
    public final Object peer;
    public final String title;
    public final String subtitle;
    public final String searchText;
    public final boolean self;
    public final boolean contactOnly;

    ChatEntry(long id, Object peer, String title, String subtitle, String username, boolean self, boolean contactOnly) {
        this.id = id;
        this.peer = peer;
        this.title = title;
        this.subtitle = subtitle;
        this.self = self;
        this.contactOnly = contactOnly;
        String search = title == null ? "" : title.toLowerCase();
        if (username != null && !username.isEmpty()) search = search + " @" + username.toLowerCase();
        this.searchText = search;
    }
}
