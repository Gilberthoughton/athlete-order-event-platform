-- Track a pending gap in the projected event stream.
--
-- `events.global_position` comes from a sequence, so a position is allocated when
-- a row is INSERTed, not when its transaction commits. A transaction holding
-- position N can therefore become visible *after* one holding N+1. A projector
-- that checkpoints the highest position it has seen would store N+1 and never
-- read N again, permanently skipping a committed event.
--
-- The projector now advances only through a contiguous prefix of positions and
-- stops at the first gap. Because a gap left by a rolled-back transaction never
-- fills, the missing position and when it was first observed are recorded here so
-- it can be skipped once no in-flight transaction could still commit into it.
ALTER TABLE projection_checkpoints
    ADD COLUMN pending_gap_position   BIGINT,
    ADD COLUMN pending_gap_first_seen TIMESTAMPTZ;
