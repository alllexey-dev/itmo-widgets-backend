# Account deletion

A user asks to delete their ITMO.Widgets account; the owner deletes it by hand
with [`account-deletion.sql`](account-deletion.sql). There is no endpoint, no
Core call and no app screen for it: the app and the site only explain how to
ask (`https://widgets.alllexey.dev/delete-account`). The deadline is 30 days
from the request.

The script needs the V10 schema (Backend 1.7). On an older schema it stops at
the first missing table and changes nothing.

## What an account holds

| Data | Tables |
|---|---|
| Identity from the ITMO.ID token | `users` (ISU, name, picture), `user_groups` |
| Settings and roles | `user_settings`, `user_roles`, `user_restrictions` |
| Devices and web sign-in | `devices` (FCM token, device name, last reported app build), `web_sessions`, `web_login_challenges` |
| Friends | `friendships` (both directions) |
| Schedule | `lessons` (by ISU, no foreign key), `user_subject_flows` |
| Sport | `sport_auto_sign_entries`, `sport_free_sign_entries`, `user_sport_lessons` |
| Subject links | `subject_links`, `subject_link_revisions`, `subject_link_votes`, `subject_link_pins` |
| Teacher reviews | `teacher_reviews`, `teacher_review_revisions`, `teacher_review_votes`, `teacher_review_flows`, `external_teacher_review_votes` |
| Moderation | `moderation_reports` (as reporter), `moderation_decisions`, `moderation_settings` and `admin_audit` (as moderator or admin) |

Not tied to an account and left alone: `isu_potok_members` (the ISU flow
cache), `external_teacher_reviews`, `teacher_summaries`,
`teacher_summary_state`, `service_credentials`, `sport_update_logs`. `app_settings.updated_by`,
`service_credentials.updated_by` and `teacher_summaries.hidden_by` become
`NULL` through their foreign keys.

## What the script does

One transaction with `lock_timeout = 5s`. It takes the moderation locks of
both target types and the user row (the services lock in the same order), then:

- **Published content stays.** A subject link that others see now (`FLOW` or
  `ALL`, not hidden, with an approved revision) and a teacher review that others
  see now (not hidden, with an approved revision) move to a placeholder user
  created for this deletion: `isu = -<ISU>`, name «Удалённый пользователь», no
  picture, the registration time of the account, all audiences `NOBODY`. The
  rows take the content of the latest approved revision, so an unpublished draft
  does not stay; a pending revision becomes `WITHDRAWN` and its open case
  `WITHDRAWN`. Reviews become `anonymous = true`; a pending ISU check ends as
  `UNVERIFIED` (it needs the author's ISU) and its candidate flows go. The
  placeholder keeps only the schedule flows that label its `FLOW` links. Votes,
  reports and pins of others on this content stay.
- **Unpublished content goes** the way `SubjectLinkService.delete` and
  `TeacherReviewService.delete` remove it: private, hidden and never approved
  links, hidden and never approved reviews, their revisions, votes, pins and
  flows; their open cases are withdrawn (cases stay as history), reports on
  their revisions are removed.
- **Votes** of the account are deleted and the `score` of every voted link,
  review and Reviews copy is recalculated.
- **History stays whole.** Reports the account filed, its moderation decisions,
  restrictions it revoked, moderation settings it changed and its admin audit
  entries name the placeholder; audit targets `user:<ISU>` become
  `user:-<ISU>`.
- **Everything else is deleted**, the `users` row last. A reference the script
  does not know about fails that delete and rolls everything back.

At the end it prints one line per step: table, action, affected rows. A second
run for the same ISU prints `not found, nothing changed`. An account recreated
after the deletion (see below) is deleted again into the same placeholder.

## Procedure

Run the commands from the Backend checkout on the owner's machine. Production
lives in `/mnt/raid/srv/web/itmowidgets`; `psql` runs inside the `database`
container (see [deployment](deployment.md)). Do not put the ISU into files in
git, tickets or chat logs.

1. **Request.** The user writes to Telegram `https://t.me/itmowidgets` or to
   `alllexey.dev@gmail.com` with their ISU number. Note the time of the request.
2. **Confirmation.** Ask the user to sign in to the web version
   `https://widgets.alllexey.dev/app/` with «Вход на сайт» in the app after the
   request: that proves they hold the signed-in app of this ISU. Then ask them
   to turn off «Подключение к ITMO.Widgets» in the app settings; otherwise the
   app's next request creates an empty account again (`JwtAuthFilter`). Check
   that a web session started after the request:

   ```bash
   read -r ISU    # the requester's ISU
   [[ $ISU =~ ^[1-9][0-9]*$ ]] || echo 'not an ISU'
   ssh alllexey.dev "cd /mnt/raid/srv/web/itmowidgets && docker compose --env-file .env -f compose.yaml exec -T database \
     sh -ec 'exec psql -X -v ON_ERROR_STOP=1 -v isu=$ISU -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\"'" <<'SQL'
   SELECT max(ws.created_at) AS last_web_sign_in
   FROM web_sessions ws JOIN users u ON u.id = ws.user_id
   WHERE u.isu = :isu;
   SQL
   ```

   No row or an earlier time: ask again; do not delete without it.
3. **Backup.** On the server (`ssh alllexey.dev`, then
   `cd /mnt/raid/srv/web/itmowidgets`):

   ```bash
   umask 077
   mkdir -p /mnt/raid/backups/itmowidgets
   BACKUP_FILE=/mnt/raid/backups/itmowidgets/account-deletion-$(date -u +%Y%m%dT%H%M%SZ).dump
   docker compose --env-file .env -f compose.yaml exec -T database sh -ec \
     'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' > "$BACKUP_FILE"
   test -s "$BACKUP_FILE"
   docker compose --env-file .env -f compose.yaml exec -T database pg_restore --list < "$BACKUP_FILE" > /dev/null
   ```

   The dump holds the deleted account: delete it once the result is verified,
   no later than the 30-day deadline. Deploy dumps under
   `/mnt/raid/backups/deploys/` keep the account until they are rotated.
4. **Run.** From the Backend checkout, with `ISU` from step 2:

   ```bash
   ssh alllexey.dev "cd /mnt/raid/srv/web/itmowidgets && docker compose --env-file .env -f compose.yaml exec -T database \
     sh -ec 'exec psql -X -v ON_ERROR_STOP=1 -v isu=$ISU -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\"'" \
     < docs/ops/account-deletion.sql
   ```

   It ends with `COMMIT` after the report. Any error rolls the whole
   transaction back; a lock timeout means a concurrent request held a row, run
   it again.
5. **Verify** with the same `psql` call as in step 2:

   ```sql
   SELECT count(*) AS account FROM users WHERE isu = :isu;
   SELECT u.name,
          (SELECT count(*) FROM subject_links l WHERE l.owner_id = u.id) AS links,
          (SELECT count(*) FROM teacher_reviews t WHERE t.author_id = u.id) AS reviews,
          (SELECT bool_and(t.anonymous) FROM teacher_reviews t WHERE t.author_id = u.id) AS anonymous
   FROM users u WHERE u.isu = -:isu;
   ```

   `account` is 0; the placeholder is «Удалённый пользователь» with the kept
   links and anonymous reviews (or no row when nothing was published). In the
   app, a kept link names «Удалённый пользователь» as its author and a kept
   review has no author. If `account` is 1 again later, the app was still
   connected: repeat step 4.
6. **Answer** the user in the channel of the request:

   > Аккаунт ITMO.Widgets удалён: профиль, друзья, устройства, расписание,
   > записи и очереди на спорт, голоса и личные ссылки. Опубликованные ссылки
   > и отзывы остались без вашего имени, автор — «Удалённый пользователь».
   > Данные на телефоне удаляются вместе с приложением или выходом из него.

7. **Log.** Append one line to `/mnt/raid/srv/web/itmowidgets/account-deletions.log`
   (mode 0600, server only): the request number, the channel, the dates of the
   request and of the deletion. No ISU and no name.

## Known limits

- The placeholder is an ordinary user row: the admin user list shows it with a
  negative ISU, and `GET /api/users/{isu}` answers for it with every capability
  off. It has no devices, so nothing is ever delivered to it.
- Android opens a profile from a link's author («Автор: …» in
  `LinkActionsBottomSheet`) with the placeholder's negative ISU; the screen shows
  the placeholder without schedule, sport or friends.

## The service

`AccountDeletionService` (`feature/users/service`) is the same deletion in
code: the script's statements in the script's order, as native SQL through
`JdbcTemplate`, in one transaction with the same locks and `lock_timeout`. It
adds one `admin_audit` row, `ACCOUNT_DELETED` with the placeholder as actor
and `user:-<ISU>` as target, and prints no report. Nothing calls it yet; a
change to the script needs the same change there.

## Tests

`AccountDeletionRunbookTest` runs this file with `psql` inside the test
PostgreSQL against an account with every kind of row, two other accounts and
their mutual data. It checks that the account's rows are gone, that published
links and reviews are shown to another user through `SubjectLinkViews` and
`TeacherReviewViews` under the placeholder (anonymous review, flow label kept),
that drafts are withdrawn with their cases and unpublished content is deleted
with its reports, that scores are recalculated, that moderation history and the
audit name the placeholder, that `UserPrivacyService.userDataFor` gives the
placeholder's name without audiences, that the other accounts are unchanged,
that a second run changes nothing, that a recreated account goes into the same
placeholder and that a non-positive ISU is refused. It also pins every foreign
key to `users`: a migration that adds one fails it until this script handles
the new reference.

`AccountDeletionServiceTest` runs the service and the script on the same world
and compares every table, apart from the placeholder's id, the deletion time and
the service's audit row; a second call of the service changes nothing.
