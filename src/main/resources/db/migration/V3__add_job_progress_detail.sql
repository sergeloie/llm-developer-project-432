-- Progress detail: which round of which stage a job is on. Small, and the difference between a
-- client seeing "generating" and a client seeing "generating, attempt 2 of 3".
--
-- Added separately rather than in the create, because the durable-execution step is what made the
-- need visible; folding it into the original migration would have rewritten history already
-- applied.
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS detail TEXT;
