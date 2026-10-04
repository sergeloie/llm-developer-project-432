package com.carddraft.agents;

/**
 * A chunk: a normalised fragment with the metadata retrieval and citation need.
 *
 * <p>The section is not decoration. It is the slot the embedding model's card asks for on the
 * document side, so a chunk that forgot its section would also be embedded worse — the two
 * requirements happen to be the same requirement.
 *
 * @param documentId the document this came from
 * @param ordinal    position within the document, so ordering survives a round trip
 * @param section    the heading it falls under; also the embedding model's title slot
 * @param table      true when the chunk is a whole table row, which is never split
 */
public record Chunk(String documentId, int ordinal, int page, String section,
                    String text, boolean table) {
}
