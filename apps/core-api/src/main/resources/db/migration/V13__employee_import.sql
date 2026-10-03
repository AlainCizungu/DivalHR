-- MVP-020 (Issue #45, architect decision E1-E16 and amendments A20-1..A20-6): employees, their
-- first employment, and the employee import that creates them. The first tables of the people
-- module; only the people module reads or writes them.
--
-- Hierarchy units (legal entity, site, department, cost center, team) are referenced by ID and
-- validated through the tenant module's OrganizationPlacementDirectory port, inside the writing
-- transaction; there are deliberately no foreign keys into tenant unit tables (E13: the people
-- schema stays private and extractable; units cannot be deleted or moved today). As for the
-- identity schema, tenant_id references the tenant root tenant.organization.
--
-- Text limits count Unicode code points of NFC-normalized text (A20-6): char_length on a UTF-8
-- database counts code points, and IS NFC NORMALIZED rejects any other normal form.
--
-- Personal data (approved classification, R20-2): the employee number and names are Confidential;
-- employment dates (start, end) and placement (legal entity, site, department, cost center, team)
-- are Restricted HR. Staged values in people.employee_import_row inherit the classification of
-- the field they stage. Staging holds normalized values of valid rows only, and only while the
-- import is open (VALIDATED). Employee IDs are personal-data references (A20-2): import rows never
-- keep a link to the employee they created.
--
-- Rollback: db/rollback/V13__rollback.sql (manual; never run by Flyway; refuses while any
-- employee or import row exists).

-- ---------------------------------------------------------------------------------------------
-- Person names (R20-1): the database applies the import's name grammar itself, so no write path
-- can store a name the application would refuse. RowValidator accepts NFC text of 1-100 code
-- points, trimmed, without repeated spaces, that starts with a letter (\p{L}) and continues with
-- letters, combining marks (\p{M}), spaces, apostrophes (' and U+2019), periods and hyphens.
-- PostgreSQL regular expressions have no Unicode property classes, and [[:alpha:]] depends on the
-- server locale (ASCII only under C), so the two classes below list the letter and mark code points
-- explicitly. They were derived from the Unicode General Category data of versions 14.0 to 18.0:
-- for every assigned code point the database accepts exactly what the application accepts, ASCII
-- included. The only difference is code points still unassigned in Unicode 18.0, which the
-- application rejects (not letters) and the database accepts. EmployeeNameGrammarDriftTest checks
-- these ranges against the running JVM's Unicode data for every code point.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION people.person_name_valid(name text) RETURNS boolean
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $fn$
SELECT name IS NFC NORMALIZED
    AND char_length(name) BETWEEN 1 AND 100
    AND name = btrim(name)
    AND name !~ '  '
    AND name ~ ('^' ||
        -- First character: a letter.
        '[A-Za-z\u00AA\u00B5\u00BA\u00C0-\u00D6\u00D8-\u00F6\u00F8-\u02C1\u02C6-\u02D1\u02E0-'
        || '\u02E4\u02EC\u02EE\u0370-\u0374\u0376-\u037D\u037F-\u0383\u0386\u0388-\u03F5\u03F7-'
        || '\u0481\u048A-\u0559\u0560-\u0588\u058B-\u058C\u0590\u05CA-\u05F2\u05F5-\u05FF\u0620-'
        || '\u064A\u066E-\u066F\u0671-\u06D3\u06D5\u06E5-\u06E6\u06EE-\u06EF\u06FA-\u06FC\u06FF'
        || '\u070E\u0710\u0712-\u072F\u074B-\u07A5\u07B1-\u07BF\u07CA-\u07EA\u07F4-\u07F5\u07FA-'
        || '\u07FC\u0800-\u0815\u081A\u0824\u0828\u082E-\u082F\u083F-\u0858\u085C-\u085D\u085F-'
        || '\u0887\u0889-\u088F\u0892-\u0896\u08A0-\u08C9\u0904-\u0939\u093D\u0950\u0958-\u0961'
        || '\u0971-\u0980\u0984-\u09BB\u09BD\u09C5-\u09C6\u09C9-\u09CA\u09CE-\u09D6\u09D8-\u09E1'
        || '\u09E4-\u09E5\u09F0-\u09F1\u09FC\u09FF-\u0A00\u0A04-\u0A3B\u0A3D\u0A43-\u0A46\u0A49-'
        || '\u0A4A\u0A4E-\u0A50\u0A52-\u0A65\u0A72-\u0A74\u0A77-\u0A80\u0A84-\u0ABB\u0ABD\u0AC6'
        || '\u0ACA\u0ACE-\u0AE1\u0AE4-\u0AE5\u0AF2-\u0AF9\u0B00\u0B04-\u0B3B\u0B3D\u0B45-\u0B46'
        || '\u0B49-\u0B4A\u0B4E-\u0B52\u0B58-\u0B61\u0B64-\u0B65\u0B71\u0B78-\u0B81\u0B83-\u0BBD'
        || '\u0BC3-\u0BC5\u0BC9\u0BCE-\u0BD6\u0BD8-\u0BE5\u0BFB-\u0BFF\u0C05-\u0C3B\u0C3D\u0C45'
        || '\u0C49\u0C4E-\u0C54\u0C57-\u0C61\u0C64-\u0C65\u0C70-\u0C76\u0C80\u0C85-\u0CBB\u0CBD'
        || '\u0CC5\u0CC9\u0CCE-\u0CD4\u0CD7-\u0CE1\u0CE4-\u0CE5\u0CF0-\u0CF2\u0CF4-\u0CFF\u0D04-'
        || '\u0D3A\u0D3D\u0D45\u0D49\u0D4E\u0D50-\u0D56\u0D5F-\u0D61\u0D64-\u0D65\u0D7A-\u0D80'
        || '\u0D84-\u0DC9\u0DCB-\u0DCE\u0DD5\u0DD7\u0DE0-\u0DE5\u0DF0-\u0DF1\u0DF5-\u0E30\u0E32-'
        || '\u0E33\u0E3B-\u0E3E\u0E40-\u0E46\u0E5C-\u0EB0\u0EB2-\u0EB3\u0EBD-\u0EC7\u0ECF\u0EDA-'
        || '\u0F00\u0F40-\u0F70\u0F88-\u0F8C\u0F98\u0FBD\u0FCD\u0FDB-\u102A\u103F\u1050-\u1055'
        || '\u105A-\u105D\u1061\u1065-\u1066\u106E-\u1070\u1075-\u1081\u108E\u10A0-\u10FA\u10FC-'
        || '\u135C\u137D-\u138F\u139A-\u13FF\u1401-\u166C\u166F-\u167F\u1681-\u169A\u169D-\u16EA'
        || '\u16F1-\u1711\u1716-\u1731\u1737-\u1751\u1754-\u1771\u1774-\u17B3\u17D7\u17DC\u17DE-'
        || '\u17DF\u17EA-\u17EF\u17FA-\u17FF\u181A-\u1884\u1887-\u18A8\u18AA-\u191F\u192C-\u192F'
        || '\u193C-\u193F\u1941-\u1943\u1950-\u19CF\u19DB-\u19DD\u1A00-\u1A16\u1A1C-\u1A1D\u1A20-'
        || '\u1A54\u1A5F\u1A7D-\u1A7E\u1A8A-\u1A8F\u1A9A-\u1A9F\u1AA7\u1AAE-\u1AAF\u1B05-\u1B33'
        || '\u1B45-\u1B4D\u1B83-\u1BA0\u1BAE-\u1BAF\u1BBA-\u1BE5\u1BF4-\u1BFB\u1C00-\u1C23\u1C38-'
        || '\u1C3A\u1C4A-\u1C4F\u1C5A-\u1C7D\u1C80-\u1CBF\u1CC8-\u1CCF\u1CE9-\u1CEC\u1CEE-\u1CF3'
        || '\u1CF5-\u1CF6\u1CFA-\u1DBF\u1E00-\u1FBC\u1FBE\u1FC2-\u1FCC\u1FD0-\u1FDC\u1FE0-\u1FEC'
        || '\u1FF0-\u1FFC\u1FFF\u2071-\u2073\u207F\u208F-\u209F\u2102\u2107\u210A-\u2113\u2115'
        || '\u2119-\u211D\u2124\u2126\u2128\u212A-\u212D\u212F-\u2139\u213C-\u213F\u2145-\u2149'
        || '\u214E\u2183-\u2184\u218C-\u218F\u2C00-\u2CE4\u2CEB-\u2CEE\u2CF2-\u2CF8\u2D00-\u2D6F'
        || '\u2D71-\u2D7E\u2D80-\u2DDF\u2E2F\u3005-\u3006\u3031-\u3035\u303B-\u303C\u3040-\u3098'
        || '\u309D-\u309F\u30A1-\u30FA\u30FC-\u318F\u31A0-\u31BF\u31E6-\u31EE\u31F0-\u31FF\u321F'
        || '\u3400-\u4DBF\u4E00-\uA48F\uA4C7-\uA4FD\uA500-\uA60C\uA610-\uA61F\uA62A-\uA66E\uA67F-'
        || '\uA69D\uA6A0-\uA6E5\uA6F8-\uA6FF\uA717-\uA71F\uA722-\uA788\uA78B-\uA801\uA803-\uA805'
        || '\uA807-\uA80A\uA80C-\uA822\uA82D-\uA82F\uA83A-\uA873\uA878-\uA87F\uA882-\uA8B3\uA8C6-'
        || '\uA8CD\uA8DA-\uA8DF\uA8F2-\uA8F7\uA8FB\uA8FD-\uA8FE\uA90A-\uA925\uA930-\uA946\uA954-'
        || '\uA95E\uA960-\uA97F\uA984-\uA9B2\uA9CE-\uA9CF\uA9DA-\uA9DD\uA9E0-\uA9E4\uA9E6-\uA9EF'
        || '\uA9FA-\uAA28\uAA37-\uAA42\uAA44-\uAA4B\uAA4E-\uAA4F\uAA5A-\uAA5B\uAA60-\uAA76\uAA7A'
        || '\uAA7E-\uAAAF\uAAB1\uAAB5-\uAAB6\uAAB9-\uAABD\uAAC0\uAAC2-\uAADD\uAAE0-\uAAEA\uAAF2-'
        || '\uAAF4\uAAF7-\uAB5A\uAB5C-\uAB69\uAB6C-\uABE2\uABEE-\uABEF\uABFA-\uD7FF\uF900-\uFB1D'
        || '\uFB1F-\uFB28\uFB2A-\uFBB1\uFBD3-\uFD3D\uFD50-\uFD8F\uFD92-\uFDC7\uFDD0-\uFDFB\uFE70-'
        || '\uFEFE\uFF21-\uFF3A\uFF41-\uFF5A\uFF66-\uFFDC\U00010000-\U000100FF\U00010103-'
        || '\U00010106\U00010134-\U00010136\U0001018F\U0001019D-\U0001019F\U000101A1-\U000101CF'
        || '\U000101FE-\U000102DF\U000102FC-\U0001031F\U00010324-\U00010340\U00010342-\U00010349'
        || '\U0001034B-\U00010375\U0001037B-\U0001039E\U000103A0-\U000103CF\U000103D6-\U0001049F'
        || '\U000104AA-\U0001056E\U00010570-\U00010856\U00010860-\U00010876\U00010880-\U000108A6'
        || '\U000108B0-\U000108FA\U00010900-\U00010915\U0001091C-\U0001091E\U00010920-\U0001093E'
        || '\U00010940-\U000109BB\U000109BE-\U000109BF\U000109D0-\U000109D1\U00010A00\U00010A04'
        || '\U00010A07-\U00010A0B\U00010A10-\U00010A37\U00010A3B-\U00010A3E\U00010A49-\U00010A4F'
        || '\U00010A59-\U00010A7C\U00010A80-\U00010A9C\U00010AA0-\U00010AC7\U00010AC9-\U00010AE4'
        || '\U00010AE7-\U00010AEA\U00010AF7-\U00010B38\U00010B40-\U00010B57\U00010B60-\U00010B77'
        || '\U00010B80-\U00010B98\U00010B9D-\U00010BA8\U00010BB0-\U00010CF9\U00010D00-\U00010D23'
        || '\U00010D28-\U00010D2F\U00010D3A-\U00010D3F\U00010D4A-\U00010D68\U00010D6F-\U00010D8D'
        || '\U00010D90-\U00010E5F\U00010E7F-\U00010EAA\U00010EAE-\U00010EC8\U00010ED9-\U00010EEF'
        || '\U00010F00-\U00010F1C\U00010F27-\U00010F45\U00010F5A-\U00010F81\U00010F8A-\U00010FC4'
        || '\U00010FCC-\U00010FFF\U00011003-\U00011037\U0001104E-\U00011051\U00011071-\U00011072'
        || '\U00011075-\U0001107E\U00011083-\U000110AF\U000110C3-\U000110CC\U000110CE-\U000110EF'
        || '\U000110FA-\U000110FF\U00011103-\U00011126\U00011135\U00011144\U00011147-\U00011172'
        || '\U00011176-\U0001117F\U00011183-\U000111B2\U000111C1-\U000111C4\U000111DA\U000111DC'
        || '\U000111E0\U000111F5-\U0001122B\U0001123F-\U00011240\U00011242-\U000112A8\U000112AA-'
        || '\U000112DE\U000112EB-\U000112EF\U000112FA-\U000112FF\U00011304-\U0001133A\U0001133D'
        || '\U00011345-\U00011346\U00011349-\U0001134A\U0001134E-\U00011356\U00011358-\U00011361'
        || '\U00011364-\U00011365\U0001136D-\U0001136F\U00011375-\U000113B7\U000113C1\U000113C3-'
        || '\U000113C4\U000113C6\U000113CB\U000113D1\U000113D3\U000113D6\U000113D9-\U000113E0'
        || '\U000113E3-\U00011434\U00011447-\U0001144A\U0001145C\U0001145F-\U000114AF\U000114C4-'
        || '\U000114C5\U000114C7-\U000114CF\U000114DA-\U000115AE\U000115B6-\U000115B7\U000115D8-'
        || '\U000115DB\U000115DE-\U0001162F\U00011644-\U0001164F\U0001165A-\U0001165F\U0001166D-'
        || '\U000116AA\U000116B8\U000116BA-\U000116BF\U000116CA-\U000116CF\U000116E4-\U0001171C'
        || '\U0001172C-\U0001172F\U00011740-\U0001182B\U0001183C-\U000118DF\U000118F3-\U0001192F'
        || '\U00011936\U00011939-\U0001193A\U0001193F\U00011941\U00011947-\U0001194F\U0001195A-'
        || '\U000119D0\U000119D8-\U000119D9\U000119E1\U000119E3\U000119E5-\U00011A00\U00011A0B-'
        || '\U00011A32\U00011A3A\U00011A48-\U00011A50\U00011A5C-\U00011A89\U00011A9D\U00011AA3-'
        || '\U00011AFF\U00011B0A-\U00011B5F\U00011B68-\U00011BE0\U00011BE2-\U00011BEF\U00011BFA-'
        || '\U00011C2E\U00011C37\U00011C40\U00011C46-\U00011C4F\U00011C6D-\U00011C6F\U00011C72-'
        || '\U00011C91\U00011CA8\U00011CB7-\U00011D30\U00011D37-\U00011D39\U00011D3B\U00011D3E'
        || '\U00011D46\U00011D48-\U00011D4F\U00011D5A-\U00011D89\U00011D8F\U00011D92\U00011D98-'
        || '\U00011D9F\U00011DAA-\U00011DDF\U00011DEA-\U00011DEF\U00011DF1-\U00011EF2\U00011EF9-'
        || '\U00011EFF\U00011F02\U00011F04-\U00011F33\U00011F3B-\U00011F3D\U00011F5B-\U00011FBF'
        || '\U00011FF2-\U00011FFE\U00012000-\U000123FF\U00012480-\U0001254F\U00012687-\U00012FF0'
        || '\U00012FF3-\U0001342F\U00013441-\U00013446\U00013456-\U0001611D\U0001613A-\U00016A5F'
        || '\U00016A6A-\U00016A6D\U00016A70-\U00016ABF\U00016ACA-\U00016AEF\U00016AF6-\U00016B2F'
        || '\U00016B40-\U00016B43\U00016B46-\U00016B4F\U00016B5A\U00016B62-\U00016D6C\U00016D7A-'
        || '\U00016E7F\U00016E9B-\U00016F4E\U00016F50\U00016F88-\U00016F8E\U00016F93-\U00016FE1'
        || '\U00016FE3\U00016FE5-\U00016FEF\U00016FF2-\U00016FF3\U00016FF7-\U0001BC9B\U0001BCA4-'
        || '\U0001CBFF\U0001CCFD-\U0001CCFF\U0001CEB4-\U0001CEB9\U0001CED1\U0001CED5-\U0001CEDC'
        || '\U0001CEFE-\U0001CEFF\U0001CF2E-\U0001CF2F\U0001CF47-\U0001CF4F\U0001CFC4-\U0001CFFF'
        || '\U0001D0F6-\U0001D0FF\U0001D246-\U0001D24F\U0001D282-\U0001D2BF\U0001D2D4-\U0001D2DF'
        || '\U0001D2F4-\U0001D2FF\U0001D357-\U0001D35F\U0001D379-\U0001D6C0\U0001D6C2-\U0001D6DA'
        || '\U0001D6DC-\U0001D6FA\U0001D6FC-\U0001D714\U0001D716-\U0001D734\U0001D736-\U0001D74E'
        || '\U0001D750-\U0001D76E\U0001D770-\U0001D788\U0001D78A-\U0001D7A8\U0001D7AA-\U0001D7C2'
        || '\U0001D7C4-\U0001D7CD\U0001DA8C-\U0001DA9A\U0001DAA0\U0001DAB0-\U0001DAFF\U0001DB1D-'
        || '\U0001DFFF\U0001E007\U0001E019-\U0001E01A\U0001E022\U0001E025\U0001E02B-\U0001E08E'
        || '\U0001E090-\U0001E12F\U0001E137-\U0001E13F\U0001E14A-\U0001E14E\U0001E150-\U0001E2AD'
        || '\U0001E2AF-\U0001E2EB\U0001E2FA-\U0001E2FE\U0001E300-\U0001E4EB\U0001E4FA-\U0001E5ED'
        || '\U0001E5F0\U0001E5FB-\U0001E5FE\U0001E600-\U0001E6E2\U0001E6E4-\U0001E6E5\U0001E6E7-'
        || '\U0001E6ED\U0001E6F0-\U0001E6F4\U0001E6F6-\U0001E8C6\U0001E8D7-\U0001E943\U0001E94B-'
        || '\U0001E94F\U0001E95A-\U0001E95D\U0001E960-\U0001EC70\U0001ECB5-\U0001ED00\U0001ED3E-'
        || '\U0001EEEF\U0001EEF2-\U0001EFFF\U00020000-\U0003FFFF]'
        -- Following characters: letters, combining marks, space, apostrophes, period, hyphen.
        '[A-Za-z ''.\-\u00AA\u00B5\u00BA\u00C0-\u00D6\u00D8-\u00F6\u00F8-\u02C1\u02C6-\u02D1'
        || '\u02E0-\u02E4\u02EC\u02EE\u0300-\u0374\u0376-\u037D\u037F-\u0383\u0386\u0388-\u03F5'
        || '\u03F7-\u0481\u0483-\u0559\u0560-\u0588\u058B-\u058C\u0590-\u05BD\u05BF\u05C1-\u05C2'
        || '\u05C4-\u05C5\u05C7-\u05F2\u05F5-\u05FF\u0610-\u061A\u0620-\u065F\u066E-\u06D3\u06D5-'
        || '\u06DC\u06DF-\u06E8\u06EA-\u06EF\u06FA-\u06FC\u06FF\u070E\u0710-\u07BF\u07CA-\u07F5'
        || '\u07FA-\u07FD\u0800-\u082F\u083F-\u085D\u085F-\u0887\u0889-\u088F\u0892-\u08E1\u08E3-'
        || '\u0963\u0971-\u09E5\u09F0-\u09F1\u09FC\u09FE-\u0A65\u0A70-\u0A75\u0A77-\u0AE5\u0AF2-'
        || '\u0B65\u0B71\u0B78-\u0BE5\u0BFB-\u0C65\u0C70-\u0C76\u0C80-\u0C83\u0C85-\u0CE5\u0CF0-'
        || '\u0D4E\u0D50-\u0D57\u0D5F-\u0D65\u0D7A-\u0DE5\u0DF0-\u0DF3\u0DF5-\u0E3E\u0E40-\u0E4E'
        || '\u0E5C-\u0ECF\u0EDA-\u0F00\u0F18-\u0F19\u0F35\u0F37\u0F39\u0F3E-\u0F84\u0F86-\u0FBD'
        || '\u0FC6\u0FCD\u0FDB-\u103F\u1050-\u108F\u109A-\u109D\u10A0-\u10FA\u10FC-\u135F\u137D-'
        || '\u138F\u139A-\u13FF\u1401-\u166C\u166F-\u167F\u1681-\u169A\u169D-\u16EA\u16F1-\u1734'
        || '\u1737-\u17D3\u17D7\u17DC-\u17DF\u17EA-\u17EF\u17FA-\u17FF\u180B-\u180D\u180F\u181A-'
        || '\u193F\u1941-\u1943\u1950-\u19CF\u19DB-\u19DD\u1A00-\u1A1D\u1A20-\u1A7F\u1A8A-\u1A8F'
        || '\u1A9A-\u1A9F\u1AA7\u1AAE-\u1B4D\u1B6B-\u1B73\u1B80-\u1BAF\u1BBA-\u1BFB\u1C00-\u1C3A'
        || '\u1C4A-\u1C4F\u1C5A-\u1C7D\u1C80-\u1CBF\u1CC8-\u1CD2\u1CD4-\u1FBC\u1FBE\u1FC2-\u1FCC'
        || '\u1FD0-\u1FDC\u1FE0-\u1FEC\u1FF0-\u1FFC\u1FFF\u2019\u2071-\u2073\u207F\u208F-\u209F'
        || '\u20D0-\u20FF\u2102\u2107\u210A-\u2113\u2115\u2119-\u211D\u2124\u2126\u2128\u212A-'
        || '\u212D\u212F-\u2139\u213C-\u213F\u2145-\u2149\u214E\u2183-\u2184\u218C-\u218F\u2C00-'
        || '\u2CE4\u2CEB-\u2CF8\u2D00-\u2D6F\u2D71-\u2DFF\u2E2F\u3005-\u3006\u302A-\u302F\u3031-'
        || '\u3035\u303B-\u303C\u3040-\u309A\u309D-\u309F\u30A1-\u30FA\u30FC-\u318F\u31A0-\u31BF'
        || '\u31E6-\u31EE\u31F0-\u31FF\u321F\u3400-\u4DBF\u4E00-\uA48F\uA4C7-\uA4FD\uA500-\uA60C'
        || '\uA610-\uA61F\uA62A-\uA672\uA674-\uA67D\uA67F-\uA6E5\uA6F0-\uA6F1\uA6F8-\uA6FF\uA717-'
        || '\uA71F\uA722-\uA788\uA78B-\uA827\uA82C-\uA82F\uA83A-\uA873\uA878-\uA8CD\uA8DA-\uA8F7'
        || '\uA8FB\uA8FD-\uA8FF\uA90A-\uA92D\uA930-\uA95E\uA960-\uA9C0\uA9CE-\uA9CF\uA9DA-\uA9DD'
        || '\uA9E0-\uA9EF\uA9FA-\uAA4F\uAA5A-\uAA5B\uAA60-\uAA76\uAA7A-\uAADD\uAAE0-\uAAEF\uAAF2-'
        || '\uAB5A\uAB5C-\uAB69\uAB6C-\uABEA\uABEC-\uABEF\uABFA-\uD7FF\uF900-\uFB28\uFB2A-\uFBB1'
        || '\uFBD3-\uFD3D\uFD50-\uFD8F\uFD92-\uFDC7\uFDD0-\uFDFB\uFE00-\uFE0F\uFE20-\uFE2F\uFE70-'
        || '\uFEFE\uFF21-\uFF3A\uFF41-\uFF5A\uFF66-\uFFDC\U00010000-\U000100FF\U00010103-'
        || '\U00010106\U00010134-\U00010136\U0001018F\U0001019D-\U0001019F\U000101A1-\U000101CF'
        || '\U000101FD-\U000102E0\U000102FC-\U0001031F\U00010324-\U00010340\U00010342-\U00010349'
        || '\U0001034B-\U0001039E\U000103A0-\U000103CF\U000103D6-\U0001049F\U000104AA-\U0001056E'
        || '\U00010570-\U00010856\U00010860-\U00010876\U00010880-\U000108A6\U000108B0-\U000108FA'
        || '\U00010900-\U00010915\U0001091C-\U0001091E\U00010920-\U0001093E\U00010940-\U000109BB'
        || '\U000109BE-\U000109BF\U000109D0-\U000109D1\U00010A00-\U00010A3F\U00010A49-\U00010A4F'
        || '\U00010A59-\U00010A7C\U00010A80-\U00010A9C\U00010AA0-\U00010AC7\U00010AC9-\U00010AEA'
        || '\U00010AF7-\U00010B38\U00010B40-\U00010B57\U00010B60-\U00010B77\U00010B80-\U00010B98'
        || '\U00010B9D-\U00010BA8\U00010BB0-\U00010CF9\U00010D00-\U00010D2F\U00010D3A-\U00010D3F'
        || '\U00010D4A-\U00010D6D\U00010D6F-\U00010D8D\U00010D90-\U00010E5F\U00010E7F-\U00010EAC'
        || '\U00010EAE-\U00010EC8\U00010ECB-\U00010ECF\U00010ED9-\U00010F1C\U00010F27-\U00010F50'
        || '\U00010F5A-\U00010F85\U00010F8A-\U00010FC4\U00010FCC-\U00011046\U0001104E-\U00011051'
        || '\U00011070-\U000110BA\U000110C2-\U000110CC\U000110CE-\U000110EF\U000110FA-\U00011135'
        || '\U00011144-\U00011173\U00011176-\U000111C4\U000111C9-\U000111CC\U000111CE-\U000111CF'
        || '\U000111DA\U000111DC\U000111E0\U000111F5-\U00011237\U0001123E-\U000112A8\U000112AA-'
        || '\U000112EF\U000112FA-\U000113D3\U000113D6\U000113D9-\U0001144A\U0001145C\U0001145E-'
        || '\U000114C5\U000114C7-\U000114CF\U000114DA-\U000115C0\U000115D8-\U00011640\U00011644-'
        || '\U0001164F\U0001165A-\U0001165F\U0001166D-\U000116B8\U000116BA-\U000116BF\U000116CA-'
        || '\U000116CF\U000116E4-\U0001172F\U00011740-\U0001183A\U0001183C-\U000118DF\U000118F3-'
        || '\U00011943\U00011947-\U0001194F\U0001195A-\U000119E1\U000119E3-\U00011A3E\U00011A47-'
        || '\U00011A99\U00011A9D\U00011AA3-\U00011AFF\U00011B0A-\U00011BE0\U00011BE2-\U00011BEF'
        || '\U00011BFA-\U00011C40\U00011C46-\U00011C4F\U00011C6D-\U00011C6F\U00011C72-\U00011D4F'
        || '\U00011D5A-\U00011D9F\U00011DAA-\U00011DDF\U00011DEA-\U00011EF6\U00011EF9-\U00011F42'
        || '\U00011F5A-\U00011FBF\U00011FF2-\U00011FFE\U00012000-\U000123FF\U00012480-\U0001254F'
        || '\U00012687-\U00012FF0\U00012FF3-\U0001342F\U00013440-\U0001612F\U0001613A-\U00016A5F'
        || '\U00016A6A-\U00016A6D\U00016A70-\U00016ABF\U00016ACA-\U00016AF4\U00016AF6-\U00016B36'
        || '\U00016B40-\U00016B43\U00016B46-\U00016B4F\U00016B5A\U00016B62-\U00016D6C\U00016D7A-'
        || '\U00016E7F\U00016E9B-\U00016FE1\U00016FE3-\U00016FF3\U00016FF7-\U0001BC9B\U0001BC9D-'
        || '\U0001BC9E\U0001BCA4-\U0001CBFF\U0001CCFD-\U0001CCFF\U0001CEB4-\U0001CEB9\U0001CED1'
        || '\U0001CED5-\U0001CEDC\U0001CEFE-\U0001CF4F\U0001CFC4-\U0001CFFF\U0001D0F6-\U0001D0FF'
        || '\U0001D127-\U0001D128\U0001D165-\U0001D169\U0001D16D-\U0001D172\U0001D17B-\U0001D182'
        || '\U0001D185-\U0001D18B\U0001D1AA-\U0001D1AD\U0001D242-\U0001D244\U0001D246-\U0001D252'
        || '\U0001D25B-\U0001D25C\U0001D25F\U0001D280-\U0001D2BF\U0001D2D4-\U0001D2DF\U0001D2F4-'
        || '\U0001D2FF\U0001D357-\U0001D35F\U0001D379-\U0001D6C0\U0001D6C2-\U0001D6DA\U0001D6DC-'
        || '\U0001D6FA\U0001D6FC-\U0001D714\U0001D716-\U0001D734\U0001D736-\U0001D74E\U0001D750-'
        || '\U0001D76E\U0001D770-\U0001D788\U0001D78A-\U0001D7A8\U0001D7AA-\U0001D7C2\U0001D7C4-'
        || '\U0001D7CD\U0001DA00-\U0001DA36\U0001DA3B-\U0001DA6C\U0001DA75\U0001DA84\U0001DA8C-'
        || '\U0001DAFF\U0001DB1D-\U0001E13F\U0001E14A-\U0001E14E\U0001E150-\U0001E2EF\U0001E2FA-'
        || '\U0001E2FE\U0001E300-\U0001E4EF\U0001E4FA-\U0001E5F0\U0001E5FB-\U0001E5FE\U0001E600-'
        || '\U0001E8C6\U0001E8D0-\U0001E94F\U0001E95A-\U0001E95D\U0001E960-\U0001EC70\U0001ECB5-'
        || '\U0001ED00\U0001ED3E-\U0001EEEF\U0001EEF2-\U0001EFFF\U00020000-\U0003FFFF\U000E0100-'
        || '\U000E01EF]'
        || '*$')
$fn$;

-- ---------------------------------------------------------------------------------------------
-- people.employee: a worker known to one tenant (E3, E4)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE people.employee (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    employee_number text        NOT NULL,
    given_names     text        NOT NULL,
    family_name     text        NOT NULL,
    created_at      timestamptz NOT NULL,
    created_by      text        NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT employee_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant.organization (id),
    CONSTRAINT employee_tenant_id_unique UNIQUE (tenant_id, id),
    -- Normalized (upper-case) employee numbers are unique per tenant (E8).
    CONSTRAINT employee_number_unique UNIQUE (tenant_id, employee_number),
    CONSTRAINT employee_number_format CHECK (employee_number ~ '^[A-Z0-9][A-Z0-9._/-]{0,31}$'),
    -- Names follow the import grammar exactly (R20-1; people.person_name_valid above).
    CONSTRAINT employee_given_names_valid CHECK (people.person_name_valid(given_names)),
    CONSTRAINT employee_family_name_valid CHECK (people.person_name_valid(family_name)),
    CONSTRAINT employee_created_by_length CHECK (char_length(created_by) BETWEEN 1 AND 255),
    CONSTRAINT employee_version_non_negative CHECK (version >= 0)
);

-- ---------------------------------------------------------------------------------------------
-- people.employment: an effective-dated placement (E3, section 10). MVP-020 creates the first,
-- open-ended employment; history changes belong to MVP-021 and ends to MVP-022.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE people.employment (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    employee_id     uuid        NOT NULL,
    legal_entity_id uuid        NOT NULL,
    site_id         uuid        NOT NULL,
    department_id   uuid,
    cost_center_id  uuid,
    team_id         uuid,
    effective_from  date        NOT NULL,
    effective_to    date,
    created_at      timestamptz NOT NULL,
    created_by      text        NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT employment_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT employment_employee_same_tenant FOREIGN KEY (tenant_id, employee_id)
        REFERENCES people.employee (tenant_id, id),
    -- A department or a cost center, never both (like teams).
    CONSTRAINT employment_one_site_unit CHECK (num_nonnulls(department_id, cost_center_id) <= 1),
    -- A team always sits under a department or a cost center (derived from the team if omitted).
    CONSTRAINT employment_team_has_parent
        CHECK (team_id IS NULL OR num_nonnulls(department_id, cost_center_id) = 1),
    CONSTRAINT employment_effective_order
        CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT employment_effective_range CHECK (
        effective_from BETWEEN DATE '1900-01-01' AND DATE '2999-12-31'
        AND (effective_to IS NULL OR effective_to BETWEEN DATE '1900-01-01' AND DATE '2999-12-31')),
    CONSTRAINT employment_created_by_length CHECK (char_length(created_by) BETWEEN 1 AND 255),
    CONSTRAINT employment_version_non_negative CHECK (version >= 0)
);

CREATE INDEX employment_employee ON people.employment (tenant_id, employee_id);

-- ---------------------------------------------------------------------------------------------
-- people.employee_import: one upload, its counts and lifecycle (E5-E9, A20-2)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE people.employee_import (
    id                 uuid        PRIMARY KEY,
    tenant_id          uuid        NOT NULL,
    status             text        NOT NULL,
    created_at         timestamptz NOT NULL,
    created_by         text        NOT NULL,
    expires_at         timestamptz NOT NULL,
    file_sha256        char(64)    NOT NULL,
    preview_digest     char(64)    NOT NULL,
    delimiter          text        NOT NULL,
    header_language    text        NOT NULL,
    total_rows         integer     NOT NULL,
    valid_rows         integer     NOT NULL,
    invalid_rows       integer     NOT NULL,
    created_count      integer,
    committed_at       timestamptz,
    committed_by       text,
    closed_at          timestamptz,
    CONSTRAINT employee_import_tenant_fk FOREIGN KEY (tenant_id)
        REFERENCES tenant.organization (id),
    CONSTRAINT employee_import_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT employee_import_status_valid
        CHECK (status IN ('VALIDATED', 'COMMITTED', 'DISCARDED', 'EXPIRED')),
    CONSTRAINT employee_import_created_by_length CHECK (char_length(created_by) BETWEEN 1 AND 255),
    CONSTRAINT employee_import_expiry_after_creation CHECK (expires_at > created_at),
    CONSTRAINT employee_import_file_sha256_format CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT employee_import_preview_digest_format CHECK (preview_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT employee_import_delimiter_valid CHECK (delimiter IN ('COMMA', 'SEMICOLON')),
    CONSTRAINT employee_import_header_language_valid
        CHECK (header_language IN ('fr', 'en', 'mixed')),
    -- Hard cap above the configurable row limit (100-5,000).
    CONSTRAINT employee_import_counts_valid CHECK (
        total_rows BETWEEN 1 AND 5000
        AND valid_rows >= 0 AND invalid_rows >= 0
        AND valid_rows + invalid_rows = total_rows),
    -- Commit fields exist exactly for committed imports.
    CONSTRAINT employee_import_commit_fields CHECK (
        (status = 'COMMITTED')
            = (created_count IS NOT NULL AND committed_at IS NOT NULL AND committed_by IS NOT NULL)),
    CONSTRAINT employee_import_created_count_valid
        CHECK (created_count IS NULL OR (created_count >= 1 AND created_count = valid_rows)),
    CONSTRAINT employee_import_committed_by_length
        CHECK (committed_by IS NULL OR char_length(committed_by) BETWEEN 1 AND 255),
    -- Only open imports have no closing time.
    CONSTRAINT employee_import_closed_at CHECK ((status = 'VALIDATED') = (closed_at IS NULL))
);

-- Open-import cap per tenant, the expiry job and the retention job.
CREATE INDEX employee_import_tenant_status ON people.employee_import (tenant_id, status, expires_at);
CREATE INDEX employee_import_closed ON people.employee_import (closed_at) WHERE closed_at IS NOT NULL;
CREATE INDEX employee_import_open_expiry ON people.employee_import (expires_at)
    WHERE status = 'VALIDATED';

-- ---------------------------------------------------------------------------------------------
-- people.employee_import_row: per-row outcome; staged values only for valid rows of an open
-- import (E6). No link to the created employee is kept (A20-2).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE people.employee_import_row (
    tenant_id         uuid    NOT NULL,
    import_id         uuid    NOT NULL,
    row_number        integer NOT NULL,
    status            text    NOT NULL,
    error_columns     text[]  NOT NULL DEFAULT '{}',
    error_codes       text[]  NOT NULL DEFAULT '{}',
    employee_number   text,
    given_names       text,
    family_name       text,
    start_date        date,
    legal_entity_code text,
    site_code         text,
    department_code   text,
    cost_center_code  text,
    team_code         text,
    CONSTRAINT employee_import_row_pk PRIMARY KEY (tenant_id, import_id, row_number),
    CONSTRAINT employee_import_row_import_same_tenant FOREIGN KEY (tenant_id, import_id)
        REFERENCES people.employee_import (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT employee_import_row_number_range CHECK (row_number BETWEEN 1 AND 5000),
    CONSTRAINT employee_import_row_status_valid
        CHECK (status IN ('VALID', 'INVALID', 'CREATED', 'NOT_IMPORTED')),
    CONSTRAINT employee_import_row_errors_paired CHECK (
        cardinality(error_codes) = cardinality(error_columns) AND cardinality(error_codes) <= 10),
    -- INVALID rows carry their errors; VALID and CREATED rows none. When an import closes, VALID
    -- rows become CREATED (commit) or NOT_IMPORTED without errors (discard, expiry), and INVALID
    -- rows become NOT_IMPORTED keeping their codes.
    CONSTRAINT employee_import_row_errors_by_status CHECK (
        (status <> 'INVALID' OR cardinality(error_codes) > 0)
        AND (status NOT IN ('VALID', 'CREATED') OR cardinality(error_codes) = 0)),
    CONSTRAINT employee_import_row_error_codes_known CHECK (error_codes <@ ARRAY[
        'ROW_SHAPE', 'ROW_REQUIRED', 'ROW_TOO_LONG', 'ROW_CONTROL_CHARACTER', 'ROW_FORMAT',
        'ROW_DATE_FORMAT', 'ROW_DATE_RANGE', 'ROW_EMPLOYEE_NUMBER_REPEATED',
        'ROW_EMPLOYEE_NUMBER_EXISTS', 'ROW_UNIT_NOT_FOUND', 'ROW_UNIT_MISMATCH',
        'ROW_UNIT_NOT_EFFECTIVE', 'ROW_PARENT_AMBIGUOUS']::text[]),
    CONSTRAINT employee_import_row_error_columns_known CHECK (error_columns <@ ARRAY[
        'employee_number', 'given_names', 'family_name', 'start_date', 'legal_entity_code',
        'site_code', 'department_code', 'cost_center_code', 'team_code']::text[]),
    -- Staged values: all required values exactly for VALID rows; none otherwise (E6, A20-2).
    CONSTRAINT employee_import_row_values_only_when_valid CHECK (
        (status = 'VALID') = (employee_number IS NOT NULL AND given_names IS NOT NULL
            AND family_name IS NOT NULL AND start_date IS NOT NULL
            AND legal_entity_code IS NOT NULL AND site_code IS NOT NULL)
        AND (status = 'VALID' OR num_nonnulls(employee_number, given_names, family_name,
            start_date, legal_entity_code, site_code, department_code, cost_center_code,
            team_code) = 0)),
    CONSTRAINT employee_import_row_number_format
        CHECK (employee_number IS NULL OR employee_number ~ '^[A-Z0-9][A-Z0-9._/-]{0,31}$'),
    -- Staged names already passed the same grammar as stored names (R20-1).
    CONSTRAINT employee_import_row_names_valid CHECK (
        (given_names IS NULL OR people.person_name_valid(given_names))
        AND (family_name IS NULL OR people.person_name_valid(family_name))),
    CONSTRAINT employee_import_row_unit_codes_format CHECK (
        (legal_entity_code IS NULL OR legal_entity_code ~ '^[A-Z0-9][A-Z0-9_-]{1,19}$')
        AND (site_code IS NULL OR site_code ~ '^[A-Z0-9][A-Z0-9_-]{1,19}$')
        AND (department_code IS NULL OR department_code ~ '^[A-Z0-9][A-Z0-9_-]{1,19}$')
        AND (cost_center_code IS NULL OR cost_center_code ~ '^[A-Z0-9][A-Z0-9_-]{1,19}$')
        AND (team_code IS NULL OR team_code ~ '^[A-Z0-9][A-Z0-9_-]{1,19}$')),
    CONSTRAINT employee_import_row_start_date_range CHECK (
        start_date IS NULL OR start_date BETWEEN DATE '1900-01-01' AND DATE '2999-12-31')
);
