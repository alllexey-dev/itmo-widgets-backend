-- Flow options of a link revision (docs/contracts/subject-links.md, Moderation). A client sends the flows of the subject
-- its author could pick from; Backend stores them per FLOW or ALL revision as a JSON array of
-- {flowId, label, typeId, depth}. NULL: the client sent none (older clients, revisions before V37).
ALTER TABLE subject_link_revisions ADD COLUMN flow_options jsonb;
ALTER TABLE subject_link_revisions ADD CONSTRAINT ck_subject_link_revisions_flow_options CHECK (
    flow_options IS NULL OR (jsonb_typeof(flow_options) = 'array' AND visibility <> 'PRIVATE')
);

-- A moderator's APPROVE may move a FLOW revision to another flow of its options: flow_id is that flow, NULL for every
-- other decision. prior_flow_id joins the V33 snapshot: the revision's flow before a revertible decision, so an undo
-- restores it; NULL for other targets and for revisions without a flow.
ALTER TABLE moderation_decisions
    ADD COLUMN flow_id bigint,
    ADD COLUMN prior_flow_id bigint;
ALTER TABLE moderation_decisions ADD CONSTRAINT ck_moderation_decisions_flow CHECK (
    flow_id IS NULL OR (action = 'APPROVE' AND actor = 'MODERATOR' AND prior_revision_status IS NOT NULL)
);
ALTER TABLE moderation_decisions ADD CONSTRAINT ck_moderation_decisions_prior_flow CHECK (
    prior_flow_id IS NULL OR prior_revision_status IS NOT NULL
);

-- Snapshots taken before V37 record the flow the revision has had since it was submitted, so their undo stays possible.
UPDATE moderation_decisions d
SET prior_flow_id = r.flow_id
FROM moderation_cases c
JOIN subject_link_revisions r ON r.id = c.target_id
WHERE d.case_id = c.id
  AND c.target_type = 'SUBJECT_RESOURCE'
  AND d.prior_revision_status IS NOT NULL
  AND r.flow_id IS NOT NULL;
