package com.divalhr.core.documents.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.documents.domain.TemplateProblem.Reason;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** A30-2: every link-like, encoded or markup form is refused; ordinary punctuation is not. */
class ForbiddenConstructsTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          Voir https://exemple.cd                       | URI_SCHEME
          HtTpS://exemple.cd                            | URI_SCHEME
          http:exemple                                  | URI_SCHEME
          JavaScript:alert(1)                           | URI_SCHEME
          javascript :alert(1)                          | URI_SCHEME
          vbscript:x                                    | URI_SCHEME
          DATA:text/html,x                              | URI_SCHEME
          data:x                                        | URI_SCHEME
          file:///etc/passwd                            | URI_SCHEME
          mailto:rh@exemple.cd                          | URI_SCHEME
          tel:+243810000000                             | URI_SCHEME
          ftp:x                                         | URI_SCHEME
          blob:x                                        | URI_SCHEME
          wss:x                                         | URI_SCHEME
          custom-app:/open                              | URI_SCHEME
          h t t p s : exemple                           | URI_SCHEME
          ｈｔｔｐｓ：／／exemple                          | URI_SCHEME
          java​script:alert(1)                     | URI_SCHEME
          ja­vascript:alert(1)                     | URI_SCHEME
          Voir //exemple.cd/x                           | PROTOCOL_RELATIVE
          \\\\serveur\\partage                          | PROTOCOL_RELATIVE
          www.exemple.cd                                | WEB_ADDRESS
          WWW.Exemple.CD                                | WEB_ADDRESS
          exemple.cd/x                                  | WEB_ADDRESS
          rh.exemple.com/contrat                        | WEB_ADDRESS
          %6A%61vascript                                | ENCODED_CONTENT
          caf%C3%A9                                     | ENCODED_CONTENT
          &lt;script&gt;                                | ENCODED_CONTENT
          &#106;avascript                               | ENCODED_CONTENT
          &#x6A;avascript                               | ENCODED_CONTENT
          \\u006Aavascript                              | ENCODED_CONTENT
          \\x6a                                         | ENCODED_CONTENT
          [lien](exemple)                               | MARKDOWN_LINK
          [ref]: exemple                                | MARKDOWN_LINK
          ![image](x)                                   | MARKDOWN_LINK
          <https://exemple.cd>                          | URI_SCHEME
          <p>Texte</p>                                  | HTML_MARKUP
          </p>                                          | HTML_MARKUP
          <!-- commentaire -->                          | HTML_MARKUP
          <!DOCTYPE html>                               | HTML_MARKUP
          <?xml?>                                       | HTML_MARKUP
          <img src=x onerror=alert(1)>                  | HTML_MARKUP
          <IMG SRC=x>                                   | HTML_MARKUP
          texte onclick=x                               | HTML_MARKUP
          ONLOAD = x                                    | HTML_MARKUP
          style=color                                   | HTML_MARKUP
          href = x                                      | HTML_MARKUP
          """)
  void refuses(String line, Reason reason) {
    assertThat(ForbiddenConstructs.find(unescape(line))).as(line).contains(reason);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Article 2 : Durée",
        "Article 2: Duration",
        "De 8:00 à 14:30, du lundi au vendredi.",
        "Année 2026/27",
        "C/O le siège",
        "Le salarié et/ou son représentant",
        "« moins de 3 mois < 6 mois »",
        "Une durée > 3 mois",
        "Dupont & Fils",
        "Une prime de 10 % du salaire",
        "§ 3, alinéa 2°, 50 €",
        "Clause (a) [réservé]",
        "Voir [annexe 1] (page 3) et l’article [X] : durée",
        "Personal data: the employer processes it lawfully.",
        "Les données (data : nom, matricule) sont protégées.",
        "Le fichier : voir l’annexe",
        "Hôtel : Kinshasa",
        "Contact : rh@exemple.cd",
        "Le travailleur s’engage à respecter le règlement.",
        "Montant online: aucun",
        "Version 1.2 du règlement",
        "Section 3.1/3.2",
      })
  void allowsOrdinaryLegalPunctuation(String line) {
    assertThat(ForbiddenConstructs.find(line)).as(line).isEqualTo(Optional.empty());
  }

  private static String unescape(String text) {
    return text.replace("\\u200B", "​").replace("\\u00AD", "­");
  }
}
