-- Неуспешные попытки вызова модели: llm_call_failed тоже должен попадать в учёт,
-- иначе «доля невалидных ответов» пришлось бы считать по логам, а не по данным.
ALTER TABLE llm_calls ADD COLUMN IF NOT EXISTS outcome TEXT NOT NULL DEFAULT 'ok';
ALTER TABLE llm_calls ADD COLUMN IF NOT EXISTS error TEXT;