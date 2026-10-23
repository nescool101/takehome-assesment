terraform {
  required_providers {
    aws = { source = "hashicorp/aws" }
  }
}

variable "name" { type = string }

# Poison/crash-loop safety net only. Real retries are counted by the worker (RETRY_MAX_ATTEMPTS);
# deferrals also bump SQS ReceiveCount, so this must stay well above it.
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

resource "aws_dynamodb_table" "registry" {
  name         = "${var.name}-registry"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "engagementId"
  attribute {
    name = "engagementId"
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
}

# offerId = hash(product, targetVersion)
resource "aws_dynamodb_table" "offers" {
  name         = "${var.name}-offers"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "offerId"
  attribute {
    name = "offerId"
    type = "S"
  }
}

resource "aws_dynamodb_table" "checkpoints" {
  name         = "${var.name}-checkpoints"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "packageId"
  attribute {
    name = "packageId"
    type = "S"
  }
}

# audit trail: what summary the user saw when deciding
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
