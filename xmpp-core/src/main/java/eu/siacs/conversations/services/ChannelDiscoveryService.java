package eu.siacs.conversations.services;

import android.util.Log;
import androidx.annotation.NonNull;
import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Ordering;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.gson.Gson;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Room;
import eu.siacs.conversations.http.HttpConnectionManager;
import eu.siacs.conversations.http.services.MuclumbusService;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.manager.MultiUserChatManager;
import im.conversations.android.xmpp.model.disco.info.InfoQuery;
import im.conversations.android.xmpp.model.disco.items.Item;
import im.conversations.android.xmpp.model.disco.items.ItemsQuery;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class ChannelDiscoveryService {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // The same default Gson the Retrofit converter used before.
    private static final Gson GSON = new Gson();

    private final XmppConnectionService service;

    private OkHttpClient muclumbusClient;
    private HttpUrl muclumbusBase;

    private final Cache<String, List<Room>> cache;

    ChannelDiscoveryService(XmppConnectionService service) {
        this.service = service;
        this.cache = CacheBuilder.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build();
    }

    void initializeMuclumbusService() {
        final HttpUrl base =
                Strings.isNullOrEmpty(Config.CHANNEL_DISCOVERY)
                        ? null
                        : HttpUrl.parse(Config.CHANNEL_DISCOVERY);
        if (base == null) {
            this.muclumbusClient = null;
            this.muclumbusBase = null;
            return;
        }
        final OkHttpClient.Builder builder =
                HttpConnectionManager.okHttpClient(service).newBuilder();
        if (service.useTorToConnect()) {
            builder.proxy(HttpConnectionManager.getProxy());
        }
        this.muclumbusClient = builder.build();
        this.muclumbusBase = base;
    }

    private <T> void queryMuclumbus(
            final Request request,
            final Class<T> type,
            final Consumer<T> onBody,
            final OnChannelSearchResultsFound listener) {
        muclumbusClient
                .newCall(request)
                .enqueue(
                        new Callback() {
                            @Override
                            public void onResponse(
                                    @NonNull final Call call, @NonNull final Response response) {
                                try (final ResponseBody responseBody = response.body()) {
                                    final T body =
                                            response.isSuccessful() && responseBody != null
                                                    ? GSON.fromJson(responseBody.charStream(), type)
                                                    : null;
                                    if (body == null) {
                                        Log.d(
                                                Config.LOGTAG,
                                                "code from muclumbus=" + response.code());
                                        listener.onChannelSearchResultsFound(
                                                Collections.emptyList());
                                        return;
                                    }
                                    onBody.accept(body);
                                } catch (final RuntimeException e) {
                                    Log.d(Config.LOGTAG, "Unable to read muclumbus answer", e);
                                    listener.onChannelSearchResultsFound(Collections.emptyList());
                                }
                            }

                            @Override
                            public void onFailure(
                                    @NonNull final Call call, @NonNull final IOException e) {
                                Log.d(
                                        Config.LOGTAG,
                                        "Unable to query muclumbus on " + Config.CHANNEL_DISCOVERY,
                                        e);
                                listener.onChannelSearchResultsFound(Collections.emptyList());
                            }
                        });
    }

    void cleanCache() {
        cache.invalidateAll();
    }

    void discover(
            @NonNull final String query,
            Method method,
            OnChannelSearchResultsFound onChannelSearchResultsFound) {
        final List<Room> result = cache.getIfPresent(key(method, query));
        if (result != null) {
            onChannelSearchResultsFound.onChannelSearchResultsFound(result);
            return;
        }
        if (method == Method.LOCAL_SERVER) {
            discoverChannelsLocalServers(query, onChannelSearchResultsFound);
        } else {
            if (query.isEmpty()) {
                discoverChannelsJabberNetwork(onChannelSearchResultsFound);
            } else {
                discoverChannelsJabberNetwork(query, onChannelSearchResultsFound);
            }
        }
    }

    private void discoverChannelsJabberNetwork(final OnChannelSearchResultsFound listener) {
        if (muclumbusClient == null) {
            listener.onChannelSearchResultsFound(Collections.emptyList());
            return;
        }
        final HttpUrl url =
                muclumbusBase
                        .newBuilder()
                        .encodedPath(MuclumbusService.ROOMS_PATH)
                        .addQueryParameter("p", "1")
                        .build();
        queryMuclumbus(
                new Request.Builder().url(url).get().build(),
                MuclumbusService.Rooms.class,
                body -> {
                    final List<Room> items = nullToEmpty(body.items);
                    cache.put(key(Method.JABBER_NETWORK, ""), items);
                    listener.onChannelSearchResultsFound(items);
                },
                listener);
    }

    private void discoverChannelsJabberNetwork(
            final String query, final OnChannelSearchResultsFound listener) {
        if (muclumbusClient == null) {
            listener.onChannelSearchResultsFound(Collections.emptyList());
            return;
        }
        final HttpUrl url =
                muclumbusBase.newBuilder().encodedPath(MuclumbusService.SEARCH_PATH).build();
        final RequestBody requestBody =
                RequestBody.create(
                        GSON.toJson(new MuclumbusService.SearchRequest(query)), JSON);
        queryMuclumbus(
                new Request.Builder().url(url).post(requestBody).build(),
                MuclumbusService.SearchResult.class,
                body -> {
                    final List<Room> items =
                            nullToEmpty(body.result == null ? null : body.result.items);
                    cache.put(key(Method.JABBER_NETWORK, query), items);
                    listener.onChannelSearchResultsFound(items);
                },
                listener);
    }

    private static List<Room> nullToEmpty(final List<Room> rooms) {
        return rooms == null ? Collections.emptyList() : rooms;
    }

    private void discoverChannelsLocalServers(
            final String query, final OnChannelSearchResultsFound listener) {
        final var localMucService = getLocalMucServices();
        Log.d(Config.LOGTAG, "checking with " + localMucService.size() + " muc services");
        if (localMucService.isEmpty()) {
            listener.onChannelSearchResultsFound(Collections.emptyList());
            return;
        }
        if (!query.isEmpty()) {
            final List<Room> cached = cache.getIfPresent(key(Method.LOCAL_SERVER, ""));
            if (cached != null) {
                final List<Room> results = copyMatching(cached, query);
                cache.put(key(Method.LOCAL_SERVER, query), results);
                listener.onChannelSearchResultsFound(results);
            }
        }
        final var roomsRoomsFuture =
                Futures.successfulAsList(
                        Collections2.transform(
                                localMucService.entrySet(),
                                e -> discoverRooms(e.getValue(), e.getKey())));
        final var roomsFuture =
                Futures.transform(
                        roomsRoomsFuture,
                        rooms -> {
                            final var builder = new ImmutableList.Builder<Room>();
                            for (final var inner : rooms) {
                                if (inner == null) {
                                    continue;
                                }
                                builder.addAll(inner);
                            }
                            return builder.build();
                        },
                        MoreExecutors.directExecutor());
        Futures.addCallback(
                roomsFuture,
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(ImmutableList<Room> rooms) {
                        finishDiscoSearch(rooms, query, listener);
                    }

                    @Override
                    public void onFailure(@NonNull Throwable throwable) {
                        Log.d(Config.LOGTAG, "could not perform room search", throwable);
                    }
                },
                MoreExecutors.directExecutor());
    }

    private ListenableFuture<Collection<Room>> discoverRooms(
            final XmppConnection connection, final Jid server) {
        final var request = new Iq(Iq.Type.GET);
        request.addExtension(new ItemsQuery());
        request.setTo(server);
        final ListenableFuture<Collection<Item>> itemsFuture =
                Futures.transform(
                        connection.sendIqPacket(request),
                        iq -> {
                            final var itemsQuery = iq.getExtension(ItemsQuery.class);
                            if (itemsQuery == null) {
                                return Collections.emptyList();
                            }
                            final var items = itemsQuery.getExtensions(Item.class);
                            return Collections2.filter(items, i -> Objects.nonNull(i.getJid()));
                        },
                        MoreExecutors.directExecutor());
        final var roomsFutures =
                Futures.transformAsync(
                        itemsFuture,
                        items -> {
                            final var infoFutures =
                                    Collections2.transform(
                                            items, i -> discoverRoom(connection, i.getJid()));
                            return Futures.successfulAsList(infoFutures);
                        },
                        MoreExecutors.directExecutor());
        return Futures.transform(
                roomsFutures,
                rooms -> Collections2.filter(rooms, Objects::nonNull),
                MoreExecutors.directExecutor());
    }

    private ListenableFuture<Room> discoverRoom(final XmppConnection connection, final Jid room) {
        final var request = new Iq(Iq.Type.GET);
        request.addExtension(new InfoQuery());
        request.setTo(room);
        final var infoQueryResponseFuture = connection.sendIqPacket(request);
        return Futures.transform(
                infoQueryResponseFuture,
                result -> {
                    final var infoQuery = result.getExtension(InfoQuery.class);
                    if (infoQuery == null) {
                        return null;
                    }
                    return Room.of(room, infoQuery);
                },
                MoreExecutors.directExecutor());
    }

    private void finishDiscoSearch(
            final List<Room> rooms,
            final String query,
            final OnChannelSearchResultsFound listener) {
        Log.d(Config.LOGTAG, "finishDiscoSearch with " + rooms.size() + " rooms");
        final var sorted = Ordering.natural().sortedCopy(rooms);
        cache.put(key(Method.LOCAL_SERVER, ""), sorted);
        if (query.isEmpty()) {
            listener.onChannelSearchResultsFound(sorted);
        } else {
            List<Room> results = copyMatching(sorted, query);
            cache.put(key(Method.LOCAL_SERVER, query), results);
            listener.onChannelSearchResultsFound(sorted);
        }
    }

    private static List<Room> copyMatching(List<Room> haystack, String needle) {
        ArrayList<Room> result = new ArrayList<>();
        for (Room room : haystack) {
            if (room.contains(needle)) {
                result.add(room);
            }
        }
        return result;
    }

    private Map<Jid, XmppConnection> getLocalMucServices() {
        final ImmutableMap.Builder<Jid, XmppConnection> localMucServices =
                new ImmutableMap.Builder<>();
        for (final var account : service.getAccounts()) {
            final var connection = account.getXmppConnection();
            if (connection != null && account.isEnabled()) {
                for (final var mucService :
                        connection.getManager(MultiUserChatManager.class).getServices()) {
                    if (Jid.Invalid.isValid(mucService)) {
                        localMucServices.put(mucService, connection);
                    }
                }
            }
        }
        return localMucServices.buildKeepingLast();
    }

    private static String key(Method method, String query) {
        return String.format("%s\00%s", method, query);
    }

    public interface OnChannelSearchResultsFound {
        void onChannelSearchResultsFound(List<Room> results);
    }

    public enum Method {
        JABBER_NETWORK,
        LOCAL_SERVER
    }
}
