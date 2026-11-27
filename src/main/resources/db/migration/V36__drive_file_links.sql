-- A Google Drive file link (drive.google.com/file/d/<id>, /file/u/<n>/d/<id>, /open?id=<id>) now normalizes to the
-- Google sheet of its id (ResourceUrlPolicy): an .xlsx uploaded to Drive exports at the Sheets address, so it shares
-- one duplicate group and one sheet rule with that sheet. Links and revisions stored before keep their opened url;
-- only normalized_url moves to the new form.
UPDATE subject_links
SET normalized_url = 'https://docs.google.com/spreadsheets/d/' || substring(normalized_url
    FROM '^https://drive\.google\.com/file(?:/u/[0-9]+)?/d/([A-Za-z0-9_-]{20,128})(?:[/?].*)?$')
WHERE normalized_url ~ '^https://drive\.google\.com/file(/u/[0-9]+)?/d/[A-Za-z0-9_-]{20,128}([/?].*)?$';

UPDATE subject_links
SET normalized_url = 'https://docs.google.com/spreadsheets/d/' || substring(normalized_url
    FROM '^https://drive\.google\.com/open\?(?:.*&)?id=([A-Za-z0-9_-]{20,128})(?:&.*)?$')
WHERE normalized_url ~ '^https://drive\.google\.com/open\?(.*&)?id=[A-Za-z0-9_-]{20,128}(&.*)?$';

UPDATE subject_link_revisions
SET normalized_url = 'https://docs.google.com/spreadsheets/d/' || substring(normalized_url
    FROM '^https://drive\.google\.com/file(?:/u/[0-9]+)?/d/([A-Za-z0-9_-]{20,128})(?:[/?].*)?$')
WHERE normalized_url ~ '^https://drive\.google\.com/file(/u/[0-9]+)?/d/[A-Za-z0-9_-]{20,128}([/?].*)?$';

UPDATE subject_link_revisions
SET normalized_url = 'https://docs.google.com/spreadsheets/d/' || substring(normalized_url
    FROM '^https://drive\.google\.com/open\?(?:.*&)?id=([A-Za-z0-9_-]{20,128})(?:&.*)?$')
WHERE normalized_url ~ '^https://drive\.google\.com/open\?(.*&)?id=[A-Za-z0-9_-]{20,128}(&.*)?$';
