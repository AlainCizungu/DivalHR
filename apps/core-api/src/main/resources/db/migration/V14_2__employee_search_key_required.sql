-- MVP-021 (H16): every employee has a search key from now on (filled by V14_1 for existing
-- employees; written with the names by every later write). Derived from the names: Confidential.
ALTER TABLE people.employee
    ALTER COLUMN search_key SET NOT NULL,
    ADD CONSTRAINT employee_search_key_format CHECK (
        char_length(search_key) BETWEEN 3 AND 205
        AND left(search_key, 1) = ' ' AND right(search_key, 1) = ' '
        AND search_key !~ '  ');
