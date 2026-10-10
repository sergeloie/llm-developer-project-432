package com.carddraft.llm;

import java.util.ArrayList;
import java.util.List;

import com.carddraft.repositories.ModelCallRepository;

/**
 * A repository that keeps the rows in memory instead of writing them to the database.
 *
 * <p>The client under test fulfils the entire contract a repository user can see; the kept rows let
 * a test assert on the exact record a call produced without a database.
 */
public class RecordingModelCallRepository extends ModelCallRepository {

    public final List<ModelCallRecord> recorded = new ArrayList<>();

    public RecordingModelCallRepository() {
        super(null);
    }

    @Override
    public void write(ModelCallRecord call) {
        recorded.add(call);
    }
}
