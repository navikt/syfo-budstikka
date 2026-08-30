-- Varig tidspunkt for delivery-terminalisering. Nullable er nødvendig for READY/CLAIMED og for
-- terminale rader skrevet før alle instanser er oppgradert; historiske tidspunkt skal ikke gjettes.
ALTER TABLE delivery
    ADD COLUMN completed_at TIMESTAMPTZ;
