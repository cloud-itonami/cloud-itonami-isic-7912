# cloud-itonami-7912

Open Business Blueprint for **ISIC Rev.5 7912**: tour operator
activities (designing and assembling package tours from multiple
travel components -- flights, accommodation, transfers, excursions --
and selling them under the operator's own brand).

This repository designs a forkable OSS business for community tour
operator operations: package-design and organizer-liability-scope
management, robotics-assisted itinerary assembly and excursion-
logistics staging, and package/reconciliation records — run by a
qualified operator so a tour operator keeps its own bonding and
consumer-protection compliance history instead of renting a closed
tour-packaging platform.

## The settlement amount is recomputed, not read

`:coordinate-vendor-settlement` proposals carry an `:estimated-amount`,
and a threshold escalates large settlements to a human. Until now that
gate read the amount **straight out of the advisor's own proposal** —
the gate's only input was the number it existed to guard against. Two
consequences, both closed:

- an advisor stating a figure just under the threshold for a far larger
  settlement bypassed the human escalation entirely;
- `some->` on a missing `:estimated-amount` returned nil, so a proposal
  carrying **no amount at all** escalated to nobody.

Entities now carry a filed per-unit rate and a billable-unit count, and
`recomputed-settlement` derives the amount from those via
[`kotoba.reservation`](https://github.com/kotoba-lang/reservation). The
mismatch gate is HARD and the threshold is applied to the **recomputed**
amount, so understating cannot buy its way under it. A settlement that
cannot be recomputed is itself a HARD violation.


## Scope note: package organizer, not a booking intermediary

`cloud-itonami-isic-7911` ("Community Travel Agency Operations")
covers the SEPARATE business of retailing third-party travel products
as an intermediary, bearing intermediary rather than organizer
liability. This repository is deliberately scoped to the business of
DESIGNING and ASSEMBLING package tours -- combining flights,
accommodation, transfers and excursions into a single product sold
under the operator's own brand -- and bearing organizer liability for
the resulting package. The EU's Package Travel Directive (2015/2302)
makes this distinction explicit and imposes package-organizer-specific
obligations (insolvency protection for prepaid amounts, liability for
the proper performance of all travel services in the package) that do
not apply to a pure booking intermediary. In the UK, tour operators
selling flight-inclusive packages require ATOL (Air Travel Organiser's
License) bonding; several US states' "Seller of Travel" statutes also
distinguish package organizers from retail agents for
registration/bonding purposes.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a
**robot performs the physical domain work**. Here robots (itinerary-
assembly automation, excursion-logistics staging and confirmation)
operate under an actor that proposes actions and an independent
**Tour Operator Governor** that gates them. The governor never
releases a finished package or issues a booking confirmation itself;
`:high`/`:safety-critical` actions (a package released outside
verified bonding/insolvency-protection scope, a component booking
without a completed availability/payment check, a reconciliation
record without verified evidence) require human sign-off.

## Core Contract

```text
intake + identity + bonding/insolvency-protection scope + package design
        |
        v
Tour Operator Advisor -> Tour Operator Governor -> match, package record, or human approval
        |
        v
robot actions (gated) + package record + reconciliation record + audit ledger
```

No automated advice can release a package the governor refuses, book
a component outside its verified availability/payment scope, or
publish a reconciliation record without governor approval and audit
evidence.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `7912`). Implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs
- [`kotoba-lang/labor`](https://github.com/kotoba-lang/labor) — agent registration, dispatch, timesheet/follow-up contracts

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
