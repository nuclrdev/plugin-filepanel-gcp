package dev.nuclr.plugin.core.panel.gcp.firestore;

import java.util.List;

/**
 * One page of a listing.
 *
 * @param items     the rows of this page
 * @param nextToken what fetches the next page (a page token or a query cursor), or {@code null} when this
 *                  was the last page
 */
public record FirestorePage<T>(List<T> items, String nextToken) {

	public boolean hasMore() {
		return nextToken != null && !nextToken.isBlank();
	}
}
