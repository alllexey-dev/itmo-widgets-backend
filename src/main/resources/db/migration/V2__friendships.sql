-- One row per unordered user pair replaces the directional friend_requests table.
-- PENDING keeps requester/addressee direction; ACCEPTED is a mutual friendship.
CREATE TABLE friendships (
    id uuid PRIMARY KEY,
    requester_id uuid NOT NULL,
    addressee_id uuid NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    created_at timestamp(6) with time zone NOT NULL,
    responded_at timestamp(6) with time zone,
    CONSTRAINT ck_friendships_not_self CHECK (requester_id <> addressee_id),
    CONSTRAINT fk_friendships_requester FOREIGN KEY (requester_id) REFERENCES users (id),
    CONSTRAINT fk_friendships_addressee FOREIGN KEY (addressee_id) REFERENCES users (id),
    CONSTRAINT ck_friendships_status CHECK (status IN ('PENDING', 'ACCEPTED')),
    CONSTRAINT ck_friendships_response CHECK (
        (status = 'PENDING' AND responded_at IS NULL) OR
        (status = 'ACCEPTED' AND responded_at IS NOT NULL AND responded_at >= created_at)
    )
);
CREATE UNIQUE INDEX uq_friendships_pair ON friendships (
    LEAST(requester_id, addressee_id), GREATEST(requester_id, addressee_id)
);
CREATE INDEX idx_friendships_requester ON friendships (requester_id, status);
CREATE INDEX idx_friendships_addressee ON friendships (addressee_id, status);

-- Two reciprocal ACTIVE requests were a friendship: keep one ACCEPTED row whose
-- requester is the earlier request. A single ACTIVE request stays PENDING.
-- CANCELLED requests carry no relationship and are dropped.
INSERT INTO friendships (id, requester_id, addressee_id, status, created_at, responded_at)
SELECT
    fr.id,
    fr.from_user_id,
    fr.to_user_id,
    CASE WHEN rev.id IS NULL THEN 'PENDING' ELSE 'ACCEPTED' END,
    fr.created_at,
    CASE WHEN rev.id IS NULL THEN NULL
         ELSE GREATEST(fr.last_activated_at, rev.last_activated_at, fr.created_at, rev.created_at) END
FROM friend_requests fr
LEFT JOIN friend_requests rev
    ON rev.from_user_id = fr.to_user_id
   AND rev.to_user_id = fr.from_user_id
   AND rev.status = 'ACTIVE'
WHERE fr.status = 'ACTIVE'
  AND (
        rev.id IS NULL
     OR (fr.created_at, fr.id) < (rev.created_at, rev.id)
  );

DROP TABLE friend_requests;
