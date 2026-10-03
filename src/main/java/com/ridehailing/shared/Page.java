package com.ridehailing.shared;

import java.util.List;

/** One page of a list, newest first; {@code nextCursor} is null on the last page (LLD §13.1). */
public record Page<T>(List<T> items, String nextCursor) {

    public Page {
        items = List.copyOf(items);
    }
}
