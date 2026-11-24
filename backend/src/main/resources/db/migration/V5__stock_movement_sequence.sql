-- ===========================================================================
-- Merqo V5: a stable order for the stock movements.
--
-- Rules that shape this file:
--   * V1 to V4 are never edited. Everything new goes into this version.
--   * The movement history is read newest first. The creation moment alone is not a stable order:
--     the clock of Windows has a resolution of about fifteen milliseconds, so two movements of the
--     same request can carry the same moment and the order between them is then undefined.
--   * The sequence number is the tiebreaker. It only grows, so the order is always the same.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- The sequence of the movement numbers.
--
-- The column is written by the database, so the application never touches it and can never break
-- the order. A plain sequence with a default is used instead of an identity column, because an
-- identity column may not be updated and the rows that are already there have to be numbered once.
-- ---------------------------------------------------------------------------
CREATE SEQUENCE stock_movements_seq_sequence START WITH 1 INCREMENT BY 1;

ALTER TABLE stock_movements ADD COLUMN seq BIGINT;

-- The rows that are already there are numbered in the order of their creation moment, so the
-- history keeps its order. The append only trigger is turned off for this one update only, and
-- it is turned on again right away.
ALTER TABLE stock_movements DISABLE TRIGGER trg_stock_movements_append_only;

WITH numbered AS (
    SELECT id,
           row_number() OVER (ORDER BY created_at, id) AS position
    FROM stock_movements
)
UPDATE stock_movements m
SET seq = numbered.position
FROM numbered
WHERE m.id = numbered.id;

ALTER TABLE stock_movements ENABLE TRIGGER trg_stock_movements_append_only;

ALTER TABLE stock_movements
    ALTER COLUMN seq SET DEFAULT nextval('stock_movements_seq_sequence');

ALTER TABLE stock_movements ALTER COLUMN seq SET NOT NULL;

ALTER SEQUENCE stock_movements_seq_sequence OWNED BY stock_movements.seq;

-- The sequence is moved past the rows that are already numbered, so the next movement gets a
-- number that is larger than every number that is used.
SELECT setval(
    'stock_movements_seq_sequence',
    COALESCE((SELECT MAX(seq) FROM stock_movements), 0) + 1,
    false);

-- The order of the history uses the sequence number, so it needs its own index.
CREATE INDEX ix_stock_movements_order ON stock_movements (location_id, created_at DESC, seq DESC);

COMMENT ON COLUMN stock_movements.seq IS
    'The tiebreaker of the movement order. The database writes it, so it only grows.';
