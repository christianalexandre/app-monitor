# SDD — System Design Document

Describes the system **as it is today**. If a chapter tells you what to do, it
belongs in `guidelines/`.

Rules:

- Changed the design → change the chapter in the same PR.
- Describe what exists. Planned work lives in `../plans/`; missing work in
  `../tech-debt/`.
- New chapter: copy `_template.md` to `NN_topic.md` and add it below.

## Chapters

| # | Chapter |
| --- | --- |
| 01 | [Architecture](01_architecture.md) |
| 02 | [Development environment](02_development_environment.md) |
| 03 | [Code map](03_code_map.md) |
| 04 | [SDK connection lifecycle](04_sdk_connection.md) |
| 05 | [OkHttp capture](05_okhttp_capture.md) |
| 06 | [iOS SDK connection lifecycle](06_ios_sdk_connection.md) |
