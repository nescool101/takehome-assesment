# RESUME (update before every /clear)
Branch `read-llm-history-docs`, worktree `basketstar`. Steps 1-6 done; 7 is next (see docs/PLAN.md section 9).
- Step 5: SQS/Dynamo adapters in `backend/.../aws`, LocalStack tests (19/19 total).
- Step 6: ops API (`api/OpsApi`), `Main`, Dockerfile, compose (localstack+backend+frontend), Bruno 14/14, Playwright 3/3 (`frontend/playwright`, uses system Chrome), API gateway Terraform module.
- Open: `.env` blocked by protect-env hook, user must run `! cp .env.example .env`. Gateway throttling unverifiable on LocalStack. Plan says 3 regions, user said 2: confirm against the PDF.
- Compose: after `up`, run `terraform apply` in `infra/terraform/envs/local` (queues/tables) before backend works.
- Step 7: Locust (3 scenarios), design-doc, diagrams, ai-usage-notes.md (whole conversation saved per deliverable), PDF.
