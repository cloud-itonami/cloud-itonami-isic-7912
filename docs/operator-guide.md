# Operator Guide

## First Deployment
1. Register operator, locations, bonding/insolvency-protection scope,
   agents and itinerary-assembly automation.
2. Import existing package and billing history.
3. Run read-only bonding-scope and itinerary-assembly/excursion-
   logistics automation mission dry-runs.
4. Configure safety-class allowed sets and human sign-off paths.
5. Publish a dry-run reconciliation record and audit export.

## Minimum Production Controls
- bonding/insolvency-protection-scope validation before any package
  release
- governor gate on every robot action before dispatch
- human sign-off for :high/:safety-critical actions (a package
  released outside verified bonding scope, an unverified component
  booking, an unverified reconciliation record)
- evidence-backed reconciliation records
- audit export for every dispatch, sign-off and reconciliation record
- backup manual tour-operator process

## Certification
Certified operators must prove robot-safety integrity, bonding/
insolvency-protection discipline, evidence-backed reconciliation
records and human review for dispatch-affecting actions.
