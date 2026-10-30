# ADR 0019 — Budstikka eier arbeidsgiversakene, og produsenten velger saktype

- Status: Besluttet
- Dato: 2026-10-30

Alt innhold i Min side – arbeidsgiver skal høre til en sak, og flere apper sender innhold om samme sykmeldte. Budstikka eier derfor sakene. Produsenten velger bare `saktype`, og Budstikka legger innholdet i typens sak for lederrelasjonen, eller for virksomheten og den sykmeldte når mottakeren er en Altinn-ressurs. En ny leder får dermed sin egen sak. Typen styrer bare tittel og merkelapp; levetid, lenke og status er like for alle typer. Budstikka lagrer ingen sakstilstand, men utleder saksnøkkelen på nytt ved hver leveranse.

Dette er et bevisst og avgrenset unntak fra ADR 0001. Saktypene er en liten, lukket liste, og en ny type er en kontraktsendring. Vi valgte bort saker som produsenten eier per fagobjekt, fordi appene da må koordinere saksnøkler og beskrive saken på hver operasjon. Vi valgte også bort en sakstabell som i esyfovarsel, fordi den kom ut av synk med Fager.
