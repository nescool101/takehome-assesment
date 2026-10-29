terraform {
  required_providers {
    aws = { source = "hashicorp/aws" }
  }
}

variable "name" { type = string }

# Optional SNS topic arn for alarm notifications; empty = alarms only (visible in the console / here in the apply output).
variable "alarm_actions" {
  type    = list(string)
  default = []
}

# LocalStack community rejects PutDashboard; set false there. Real AWS gets the console dashboard.
variable "enable_dashboard" {
  type    = bool
  default = true
}

locals {
  queue = "${var.name}-fanout"
  dlq   = "${var.name}-fanout-dlq"
}

# Poison safety net tripped: jobs ran out of retries or crashed repeatedly. Pager-worthy.
resource "aws_cloudwatch_metric_alarm" "dlq_not_empty" {
  alarm_name          = "${var.name}-dlq-not-empty"
  alarm_description   = "Fan-out DLQ has messages: poison jobs need attention"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 1
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 60
  statistic           = "Maximum"
  threshold           = 1
  dimensions          = { QueueName = local.dlq }
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_actions
  ok_actions          = var.alarm_actions
}

# Work queue not draining: oldest message older than 2 visibility timeouts means stalled processing (worker down or breaker open).
resource "aws_cloudwatch_metric_alarm" "backlog_age" {
  alarm_name          = "${var.name}-backlog-age"
  alarm_description   = "Oldest fan-out job older than 600s: worker down or circuit breaker open"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "ApproximateAgeOfOldestMessage"
  namespace           = "AWS/SQS"
  period              = 60
  statistic           = "Maximum"
  threshold           = 600
  dimensions          = { QueueName = local.queue }
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_actions
  ok_actions          = var.alarm_actions
}

# The worker's own EMF lines (Log.emf, namespace "Fanout", dimension Service): outcome counters.
resource "aws_cloudwatch_metric_alarm" "worker_dead_lettered" {
  alarm_name          = "${var.name}-worker-dead-lettered"
  alarm_description   = "Worker dead-lettered jobs in the last minute (EMF counter)"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 1
  metric_name         = "DeadLettered"
  namespace           = "Fanout"
  period              = 60
  statistic           = "Sum"
  threshold           = 1
  dimensions          = { Service = var.name }
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_actions
  ok_actions          = var.alarm_actions
}

# Console dashboard over the same signals the local Grafana board shows (queue depth, outcomes, DLQ).
resource "aws_cloudwatch_dashboard" "fanout" {
  count          = var.enable_dashboard ? 1 : 0
  dashboard_name = "${var.name}-fanout"
  dashboard_body = jsonencode({
    widgets = [
      {
        x = 0, y = 0, w = 12, h = 6, type = "metric"
        properties = {
          title  = "Queue depth"
          period = 60
          view   = "timeSeries"
          stat   = "Maximum"
          metrics = [
            ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", local.queue],
            ["AWS/SQS", "ApproximateNumberOfMessagesNotVisible", "QueueName", local.queue]
          ]
        }
      },
      {
        x = 12, y = 0, w = 12, h = 6, type = "metric"
        properties = {
          title  = "DLQ"
          period = 60
          view   = "timeSeries"
          stat   = "Maximum"
          metrics = [
            ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", local.dlq]
          ]
        }
      },
      {
        x = 0, y = 6, w = 24, h = 6, type = "metric"
        properties = {
          title  = "Worker outcomes (EMF counters)"
          period = 60
          view   = "timeSeries"
          stat   = "Sum"
          metrics = [
            ["Fanout", "Done", "Service", var.name],
            ["Fanout", "Skipped", "Service", var.name],
            ["Fanout", "Deferred", "Service", var.name],
            ["Fanout", "Retried", "Service", var.name],
            ["Fanout", "DeadLettered", "Service", var.name]
          ]
        }
      }
    ]
  })
}

output "alarm_names" {
  value = [aws_cloudwatch_metric_alarm.dlq_not_empty.alarm_name,
           aws_cloudwatch_metric_alarm.backlog_age.alarm_name,
           aws_cloudwatch_metric_alarm.worker_dead_lettered.alarm_name]
}
