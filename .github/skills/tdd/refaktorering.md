# Refaktoreringskandidater

Etter en grønn TDD-syklus, se etter:

- **Duplisering** → ekstraher funksjon eller klasse
- **Lange funksjoner** → bryt ut private hjelpere (behold testene på det offentlige grensesnittet)
- **Grunne moduler** → slå sammen eller dypne, så grensesnittet blir lite og implementasjonen dyp
- **Feature envy** → flytt logikk dit dataene bor (typisk: flytt regler fra route-handler inn i domenetjenesten)
- **Primitiv besettelse** → innfør verdiobjekter (f.eks. `Fodselsnummer` fremfor `String`, `data class`/`value class`)
- **Eksisterende kode** som den nye koden avslører som problematisk

## I et Ktor-backend spesielt

- Hold route-handlere tynne: parse/valider request, deleger til en domenetjeneste, oversett resultat til respons. Forretningslogikk hører hjemme i tjenesten, ikke i `routing { }`.
- Samle konfigurasjon og avhengighetsoppsett ett sted (modul-funksjon), så `testApplication` kan sette opp en isolert variant.
- Foretrekk `value class` eller `data class` for domeneidentiteter fremfor rå `String`/`Long`, så typer fanger feil ved kompilering.

**Aldri refaktorer mens en test er RED.** Kom til GREEN først, kjør `./gradlew test`, og refaktorer så i små steg med grønne tester mellom hvert.
