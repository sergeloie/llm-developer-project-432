package com.carddraft.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * The steps that turn a recorded document into a searchable one.
 *
 * <p>An activity rather than a call from the upload endpoint, for two reasons that point the same
 * way. Both steps block and can take minutes for a large file, which is not something an HTTP
 * request thread should be holding; and they are the steps most likely to fail for reasons outside
 * the service's control — an unreadable file, a model server that is not up yet — which is exactly
 * what an engine with its own retry policy is for.
 *
 * <p>Two steps rather than one because they can fail differently and mean different things. A parse
 * that cannot read the file is the document's own fault and ends in a refusal with a reason. An
 * embedding that cannot reach the model server is nobody's fault, is worth retrying, and must not
 * cost the caller their document — so it leaves the document where it is and the engine decides.
 *
 * <p>The identifier is the only argument. The bytes live in the database, which is the reason the
 * workflow can be replayed at all: an argument carrying the file would put it into the history,
 * where it would occupy the same budget every step's input and output shares.
 */
@ActivityInterface
public interface DocumentActivities {

    @ActivityMethod
    DocumentResult parse(String documentId);

    @ActivityMethod
    DocumentResult index(String documentId);

    /**
     * What the parse achieved, as something a caller can answer on without opening the database.
     *
     * @param chunkCount zero when the document was refused, and the reason says why
     */
    record DocumentResult(String documentId, String state, int chunkCount, String reason) {
    }
}