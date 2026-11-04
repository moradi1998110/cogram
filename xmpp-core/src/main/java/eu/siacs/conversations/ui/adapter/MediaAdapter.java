package eu.siacs.conversations.ui.adapter;

import com.google.common.collect.ImmutableList;
import eu.siacs.conversations.utils.MimeUtils;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/** Keeps the MIME lists the engine shares with the Conversations media grid. */
public final class MediaAdapter {
    public static final List<String> DOCUMENT_MIMES =
            new ImmutableList.Builder<String>()
                    .add("application/pdf")
                    .add("text/x-tex")
                    .add("text/plain")
                    .addAll(MimeUtils.WORD_DOCUMENT_MIMES)
                    .build();

    public static final Collection<String> SHEET_MUSIC =
            Arrays.asList(
                    "application/vnd.recordare.musicxml", "application/vnd.recordare.musicxml+xml");

    private MediaAdapter() {}
}
