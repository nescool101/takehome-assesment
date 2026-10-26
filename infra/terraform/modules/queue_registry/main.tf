terraform {
  required_providers {
    aws = { source = "hashicorp/aws" }
  }
}

variable "name" { type = string }

# Poison/crash-loop safety net only. Real retries are counted by the worker (RETRY_MAX_ATTEMPTS);
# deferrals and retries are re-sent as new messages, so ReceiveCount only counts real redeliveries (crashes).
variable "max_receive_count" {
  type    = number
  default = 20
}

resource "aws_sqs_queue" "dlq" { name = "${var.name}-fanout-dlq" }

resource "aws_sqs_queue" "work" {
  name                       = "${var.name}-fanout"
  visibility_timeout_seconds = 300
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq.arn
    maxReceiveCount     = var.max_receive_count
  })
}

# Engagement registry (design D1): one item per (engagement, published offer). OpsApi writes it on publish and reads it
# by engagement (badge) or by offer (status, GSI "offer-index": eventually consistent).
resource "aws_dynamodb_table" "registry" {
  name         = "${var.name}-registry"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "engagementId"
  range_key    = "offer"
  attribute {
    name = "engagementId"
    type = "S"
  }
  attribute {
    name = "offer"
    type = "S"
  }
  attribute {
    name = "firmId"
    type = "S"
  }
  attribute {
    name = "templateId"
    type = "S"
  }
  global_secondary_index {
    name            = "firm-template"
    hash_key        = "firmId"
    range_key       = "templateId"
    projection_type = "ALL"
  }
  global_secondary_index {
    name            = "offer-index"
    hash_key        = "offer"
    projection_type = "ALL"
  }
}

# worker idempotency keys (engagementId|templateId|version)
resource "aws_dynamodb_table" "idempotency" {
  name         = "${var.name}-idempotency"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "k"
  attribute {
    name = "k"
    type = "S"
  }
  # DONE rows carry exp (30 days); without TTL the table grows forever
  ttl {
    attribute_name = "exp"
    enabled        = true
  }
}

# offerId = "templateId|version" (written on publish, withdrawn flag set by withdraw)
resource "aws_dynamodb_table" "offers" {
  name         = "${var.name}-offers"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "offerId"
  attribute {
    name = "offerId"
    type = "S"
  }
}

# Design-only: backfill checkpoints. The worker has no checkpointing yet, so nothing uses this table.
resource "aws_dynamodb_table" "checkpoints" {
  name         = "${var.name}-checkpoints"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "packageId"
  attribute {
    name = "packageId"
    type = "S"
  }
}

# decisions: one item per apply/decline with the offers it covered (written by OpsApi); the summary shown is not stored yet
resource "aws_dynamodb_table" "decisions" {
  name         = "${var.name}-decisions"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "engagementId"
  range_key    = "decidedAt"
  attribute {
    name = "engagementId"
    type = "S"
  }
  attribute {
    name = "decidedAt"
    type = "S"
  }
}

output "queue_url" { value = aws_sqs_queue.work.url }
output "dlq_url" { value = aws_sqs_queue.dlq.url }

# ShedLock table: the shared circuit breaker takes its half-open probe lock here (hash key "_id" is ShedLock's schema)
resource "aws_dynamodb_table" "locks" {
  name         = "${var.name}-locks"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "_id"
  attribute {
    name = "_id"
    type = "S"
  }
}
