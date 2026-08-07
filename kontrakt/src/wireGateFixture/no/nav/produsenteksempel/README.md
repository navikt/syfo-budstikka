Fixture sources for the compile-time proof of the `@InternalBudstikkaWire` gate.

They are deliberately NOT part of any Gradle source set, so nothing in the normal build compiles them.
`:kontrakt:verifyWireOptInGate` compiles this directory twice with the Kotlin compiler already on the
build's own classpath:

1. WITH `-opt-in=no.nav.budstikka.contract.InternalBudstikkaWire` — must succeed, which proves the code
   is otherwise valid and that the failure below is the gate and not a typo.
2. WITHOUT the opt-in — every file must be rejected, and every compiler error must be an opt-in error.

One file per gated concern, so the task can report exactly which part of the boundary broke.
