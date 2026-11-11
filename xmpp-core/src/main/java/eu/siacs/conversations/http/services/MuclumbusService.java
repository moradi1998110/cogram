package eu.siacs.conversations.http.services;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import eu.siacs.conversations.entities.Room;

// The JSON shapes of the search.jabber.network room directory. Phogram calls it with OkHttp
// directly, so these are plain classes rather than a Retrofit interface.
public final class MuclumbusService {

    public static final String ROOMS_PATH = "/api/1.0/rooms/unsafe";
    public static final String SEARCH_PATH = "/api/1.0/search";

    private MuclumbusService() {}

    public static class Rooms {
        int page;
        int total;
        int pages;
        public List<Room> items;
    }

    public static class SearchRequest {

        public final Set<String> keywords;

        public SearchRequest(String keyword) {
            this.keywords = Collections.singleton(keyword);
        }
    }

    public static class SearchResult {

        public Result result;

    }

    public static class Result {

        public List<Room> items;

    }

}
