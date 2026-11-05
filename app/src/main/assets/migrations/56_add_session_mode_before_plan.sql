-- Record the mode active before entering PLAN mode (e.g. AUTO) so leaving PLAN can restore it.
-- Previously leaving PLAN always fell back to BUILD, overwriting the user's auto mode after entering PLAN from AUTO.
ALTER TABLE chat_sessions ADD COLUMN modeBeforePlan TEXT DEFAULT NULL;