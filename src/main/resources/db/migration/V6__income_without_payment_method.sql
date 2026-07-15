-- 収入は支払い方法を持たない。
-- 既存の収入データの支払い方法をクリアしてから、種別ごとの CHECK 制約を
-- 「収入は payment_method_id が NULL」に更新する（支出は従来どおり必須）。
UPDATE transactions
SET
    payment_method_id = NULL
WHERE
    type = 'INCOME';

ALTER TABLE transactions
DROP CONSTRAINT ck_transactions_targets_by_type;

ALTER TABLE transactions
ADD CONSTRAINT ck_transactions_targets_by_type CHECK (
    (
        type = 'EXPENSE'
        AND category_id IS NOT NULL
        AND payment_method_id IS NOT NULL
        AND transfer_source_id IS NULL
        AND transfer_destination_id IS NULL
    )
    OR (
        type = 'INCOME'
        AND category_id IS NOT NULL
        AND payment_method_id IS NULL
        AND transfer_source_id IS NULL
        AND transfer_destination_id IS NULL
    )
    OR (
        type = 'TRANSFER'
        AND category_id IS NULL
        AND payment_method_id IS NULL
        AND transfer_source_id IS NOT NULL
        AND transfer_destination_id IS NOT NULL
    )
);
