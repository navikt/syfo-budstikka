---
name: tryggnok-ros
description: Creates or revises a NAV TryggNok ROS (risiko- og sårbarhetsvurdering) for an application or product from repository evidence and stakeholder input. Use when someone asks for a ROS, a TryggNok risk assessment, its description or summary, or a risk list; not for Robot Operating System.
argument-hint: "[system, product, or assessment scope]"
---

# Create a TryggNok ROS

Produce a reviewable Norwegian draft for TryggNok. The owning team and the
people with the relevant domain, legal, privacy, security, and operational
authority validate the facts, scoring, measures, and risk acceptance.

## 1. Establish the assessment basis

Read the user's context and the current sources that describe the assessed
system. For a repository, inspect the relevant product documentation, runtime
code, contracts, data model, deployment configuration, observability, recovery
procedures, and tests. Separate current behavior from planned behavior.

Track each material statement as observed evidence, a stakeholder statement,
an assumption, or an open question. Ask only for missing information that can
materially change scope, scoring, or acceptance; otherwise proceed with a
clearly labeled draft.

Complete this step when the assessed object, environment, lifecycle stage,
purpose, boundaries, and evidence gaps are explicit.

## 2. Map the system and its exposure

Identify the actors and owners; inputs and outputs; personal and business data;
storage, access, retention, and deletion; integrations and trust boundaries;
deployment and runtime dependencies; monitoring, recovery, and manual
operations. Include material producer and downstream responsibilities so the
assessment does not assign a risk to the wrong system.

Use `/security-review` for a concrete security or privacy review and
`/architecture-review` for a proposed cross-boundary design review when those
reviews are separately requested or required by the calling workflow. This
skill owns the TryggNok synthesis, not those reviews.

Complete this step when every material data flow and dependency is represented
or called out as an evidence gap.

## 3. Identify and assess risks

Describe each risk as a possible event with causes and consequences, not as a
missing control or a solution. Cover the risk surfaces supported by the actual
system, such as availability, integrity, confidentiality and privacy,
misdelivery, dependencies, data lifecycle, operations, migration, and recovery.
Record existing controls separately from proposed measures.

Rate the current residual risk with controls that are already implemented.
Proposed measures do not reduce the score until implemented and verified. Use
the probability, consequence, and color matrix configured for the assessment;
when it is unavailable, mark scores and risk levels as proposals for validation
instead of inventing a mapping.

Complete this step when each material risk has a theme, event, consequence,
current controls, proposed rating, and proportionate measures or an explicit
reason why no measure is proposed.

## 4. Draft the TryggNok fields

Before writing the fields, read
[the TryggNok field contract](references/tryggnok-format.md). Return the three
copy-ready sections in Norwegian Bokmål:

1. `Beskrivelse`, with the four required headings;
2. `Oppsummering av risikovurderingen`;
3. `Risikoliste`, with one complete entry per risk and valid measure statuses.

State only verified incidents, consultations, completed work, and implemented
measures as facts. Describe acceptance as pending unless an authorized owner
has accepted the residual risk. Keep assumptions and validation questions
outside the copy-ready text.

Complete this step when every required field can be pasted into TryggNok and
the summary agrees with the detailed risk list.

## 5. Return the review seam

After the copy-ready fields, list the assumptions, open questions, and strongest
source evidence that the owner should validate. Highlight which answers could
change scope, rating, measures, or conclusion. Do not enter or publish the
assessment in TryggNok without separate authorization and an available tool.

The work is complete when the draft is internally consistent, evidence and
uncertainty are distinguishable, and the owning team can review every proposed
score and measure without reconstructing the analysis.
