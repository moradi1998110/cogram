package org.telegram.cogram;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Telegram's checklists over XMPP, which has no standard for them. A list is a text message any
 * client can read, and each change to it (tasks done, undone or added) is a text message naming
 * the list, so everything stays in the message body, which OMEMO encrypts like any other text.
 * Only the last line carries what Phogram reads back:
 * <pre>
 * 📋 Shopping
 * ☐ Milk
 * ☐ Bread
 * [todo|ac|1,2]        flags: a = others can add, c = others can mark; then the tasks' ids
 *
 * ✅ Milk
 * [todo:LIST_ID|+1]    +id done, -id undone, aid added (one "➕ task" line each, in order)
 * </pre>
 * Who may change a list is checked by each client against who wrote the change, as no server
 * enforces it; changes that are not allowed, or name tasks that do not exist, are ignored.
 */
final class TodoLists {

    private static final String LIST = "📋 ";
    private static final String TASK = "☐ ";
    private static final String DONE = "✅ ";
    private static final String UNDONE = "⬜ ";
    private static final String ADDED = "➕ ";
    private static final int MAX_TASKS = 100;
    private static final int MAX_TEXT = 1024;
    private static final Pattern LIST_MARK = Pattern.compile("\\[todo\\|([ac]{0,2})\\|(\\d{1,9}(?:,\\d{1,9})*)\\]");
    private static final Pattern CHANGE_MARK = Pattern.compile("\\[todo:([^|\\]\\s]{1,128})\\|([+\\-a]\\d{1,9}(?:,[+\\-a]\\d{1,9})*)\\]");

    /** A change to a list: the tasks marked done or undone, and those added. */
    static final class Change {
        final String list;
        final List<Integer> done = new ArrayList<>();
        final List<Integer> undone = new ArrayList<>();
        final List<TLRPC.TodoItem> added = new ArrayList<>();

        Change(String list) {
            this.list = list;
        }
    }

    private TodoLists() {
    }

    static String listBody(TLRPC.TodoList todo) {
        StringBuilder body = new StringBuilder(LIST).append(clean(todo.title)).append('\n');
        StringBuilder taskIds = new StringBuilder();
        for (TLRPC.TodoItem item : todo.list) {
            body.append(TASK).append(clean(item.title)).append('\n');
            taskIds.append(taskIds.length() == 0 ? "" : ",").append(item.id);
        }
        return body.append("[todo|").append(todo.others_can_append ? "a" : "").append(todo.others_can_complete ? "c" : "")
                .append('|').append(taskIds).append(']').toString();
    }

    static String doneBody(String list, TLRPC.TodoList todo, List<Integer> done, List<Integer> undone) {
        StringBuilder body = new StringBuilder();
        StringBuilder ops = new StringBuilder();
        for (TLRPC.TodoItem item : todo.list) {
            if (done.contains(item.id)) {
                body.append(DONE).append(clean(item.title)).append('\n');
                ops.append(ops.length() == 0 ? "" : ",").append('+').append(item.id);
            } else if (undone.contains(item.id)) {
                body.append(UNDONE).append(clean(item.title)).append('\n');
                ops.append(ops.length() == 0 ? "" : ",").append('-').append(item.id);
            }
        }
        return ops.length() == 0 ? null : body.append("[todo:").append(list).append('|').append(ops).append(']').toString();
    }

    static String addedBody(String list, List<TLRPC.TodoItem> added) {
        if (added.isEmpty()) {
            return null;
        }
        StringBuilder body = new StringBuilder();
        StringBuilder ops = new StringBuilder();
        for (TLRPC.TodoItem item : added) {
            body.append(ADDED).append(clean(item.title)).append('\n');
            ops.append(ops.length() == 0 ? "" : ",").append('a').append(item.id);
        }
        return body.append("[todo:").append(list).append('|').append(ops).append(']').toString();
    }

    /** The list a message's text is, with nothing done yet, or null. */
    static TLRPC.TodoList parseList(String body) {
        if (body == null || !body.startsWith(LIST)) {
            return null;
        }
        String[] lines = body.split("\n", -1);
        Matcher mark = LIST_MARK.matcher(lines[lines.length - 1]);
        if (lines.length < 3 || !mark.matches()) {
            return null;
        }
        String[] taskIds = mark.group(2).split(",");
        if (taskIds.length != lines.length - 2 || taskIds.length > MAX_TASKS) {
            return null;
        }
        TLRPC.TodoList todo = new TLRPC.TodoList();
        todo.others_can_append = mark.group(1).contains("a");
        todo.others_can_complete = mark.group(1).contains("c");
        todo.title = text(lines[0].substring(LIST.length()));
        for (int i = 0; i < taskIds.length; i++) {
            String line = lines[i + 1];
            int id = Integer.parseInt(taskIds[i]);
            if (!line.startsWith(TASK) || find(todo, id) != null) {
                return null;
            }
            todo.list.add(item(id, line.substring(TASK.length())));
        }
        return todo;
    }

    /** The change to a list a message's text is, or null. */
    static Change parseChange(String body) {
        if (body == null) {
            return null;
        }
        String[] lines = body.split("\n", -1);
        Matcher mark = CHANGE_MARK.matcher(lines[lines.length - 1]);
        if (!mark.matches()) {
            return null;
        }
        Change change = new Change(mark.group(1));
        int addedLine = 0;
        for (String op : mark.group(2).split(",")) {
            int id = Integer.parseInt(op.substring(1));
            if (op.charAt(0) == '+') {
                change.done.add(id);
            } else if (op.charAt(0) == '-') {
                change.undone.add(id);
            } else {
                // The added tasks' lines come in the order of their ids.
                while (addedLine < lines.length - 1 && !lines[addedLine].startsWith(ADDED)) {
                    addedLine++;
                }
                if (addedLine >= lines.length - 1) {
                    return null;
                }
                change.added.add(item(id, lines[addedLine++].substring(ADDED.length())));
            }
        }
        return change;
    }

    /** Whether a change may be made by its writer: the list's author may make any. */
    static boolean allows(TLRPC.TodoList todo, Change change, boolean byAuthor) {
        return byAuthor || todo.others_can_complete && (change.added.isEmpty() || todo.others_can_append);
    }

    /** Folds the changes allowed so far into a list, as Telegram shows it. */
    static final class State {
        private final TLRPC.TodoList todo;
        private final Map<Integer, TLRPC.TodoCompletion> done = new LinkedHashMap<>();

        State(TLRPC.TodoList todo) {
            this.todo = todo;
        }

        void apply(Change change, boolean byAuthor, TLRPC.Peer by, int date) {
            if (!allows(todo, change, byAuthor)) {
                return;
            }
            for (TLRPC.TodoItem item : change.added) {
                if (find(todo, item.id) == null && todo.list.size() < MAX_TASKS) {
                    todo.list.add(item);
                }
            }
            for (int id : change.done) {
                if (find(todo, id) != null) {
                    TLRPC.TL_todoCompletion completion = new TLRPC.TL_todoCompletion();
                    completion.id = id;
                    completion.completed_by = by;
                    completion.date = date;
                    done.put(id, completion);
                }
            }
            for (int id : change.undone) {
                done.remove(id);
            }
        }

        TLRPC.TL_messageMediaToDo media() {
            TLRPC.TL_messageMediaToDo media = new TLRPC.TL_messageMediaToDo();
            media.todo = todo;
            media.completions.addAll(done.values());
            if (!media.completions.isEmpty()) {
                media.flags |= TLObject.FLAG_0;
            }
            return media;
        }
    }

    static TLRPC.TodoItem find(TLRPC.TodoList todo, int id) {
        for (TLRPC.TodoItem item : todo.list) {
            if (item.id == id) {
                return item;
            }
        }
        return null;
    }

    private static TLRPC.TodoItem item(int id, String title) {
        TLRPC.TodoItem item = new TLRPC.TodoItem();
        item.id = id;
        item.title = text(title);
        return item;
    }

    private static TLRPC.TL_textWithEntities text(String value) {
        TLRPC.TL_textWithEntities text = new TLRPC.TL_textWithEntities();
        text.text = value;
        return text;
    }

    /** One line, so a title can never pass for another task or for the list's last line. */
    private static String clean(TLRPC.TL_textWithEntities text) {
        String value = text == null || text.text == null ? "" : text.text.replaceAll("\\s+", " ").trim();
        return value.length() > MAX_TEXT ? value.substring(0, MAX_TEXT) : value;
    }
}
