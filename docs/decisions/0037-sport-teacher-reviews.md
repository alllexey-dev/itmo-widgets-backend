# 0037 Reviews of sport coaches are trusted by the sport catalog

**Decision (2026-10-26).** An own review of a teacher who has lessons in
Backend's sport catalog (`sport_lessons.teacher_isu`) is verified at once, with
the basis `SPORT_TEACHER`, and goes into the AI summary like a review verified
through ISU ([0010](0010-review-verification.md)). Backend calls neither My
ITMO nor ISU for it, on the caller's behalf or otherwise.

**Why.** PE sections are not ISU flows: coaches never appear in the ISU flow
cache or in academic pairs, so before this every review of a coach stayed
`Не подтверждён` and never reached a summary. Checking that the author attended
the coach's lessons would need My ITMO with the author's own token
(`api/sport/personal/calendar`), which the review routes never use: they read
only local data, they would not work for the web or connected clients, and the
owner rejected it. The catalog is filled by the technical My ITMO account and
lists real coaches, so the claim checked is "this person really is a coach",
not "this coach taught the author". It is the same trust as an imported
Reviews copy of a coach.

**What it allows.** Any user may write a verified review of any coach. The
review is still premoderated, votes and reports apply, and the AI summary keeps
its minimum of three reviews.

**Model.** `teacher_reviews.verified_basis` is `ISU_FLOW` (with
`verified_flow_id`) or `SPORT_TEACHER` (without a flow), set exactly when
`VERIFIED`; a row verified by an image before V27 keeps a null basis and reads
as `ISU_FLOW`. A save marks a pending review of a coach `VERIFIED` in the same
transaction; the ISU queue marks a due review of a coach without any ISU
request; V27 verified the existing reviews of coaches once. A verified review
stays verified when the coach later leaves the catalog.

**Coaches elsewhere.** `knownTeacher` counts the sport catalog, so `Написать`
appears in a coach's profile. `GET /api/teachers/{isu}/reviews/hints` tells the
editor that the teacher is a coach and puts `Физическая культура` first among
the subject suggestions. The AI summary of a coach is asked to leave out exam,
lab and homework tags, and the validator drops them.
