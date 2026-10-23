terraform {
  required_providers {
    aws = { source = "hashicorp/aws" }
  }
}

variable "name" { type = string }
variable "backend_url" {
  type        = string
  description = "ops API base URL the gateway proxies to (e.g. the ALB in front of the Fargate service)"
}
variable "rate_limit" {
  type    = number
  default = 50 # sustained req/s per API key
}
variable "burst_limit" {
  type    = number
  default = 100
}
variable "daily_quota" {
  type    = number
  default = 100000 # requests/day per API key
}

# Front door for the ops API: nothing reaches the backend without a valid API key,
# and every key is throttled + capped, so a bad client cannot flood the service.
resource "aws_api_gateway_rest_api" "ops" { name = "${var.name}-ops-api" }

resource "aws_api_gateway_resource" "proxy" {
  rest_api_id = aws_api_gateway_rest_api.ops.id
  parent_id   = aws_api_gateway_rest_api.ops.root_resource_id
  path_part   = "{proxy+}"
}

resource "aws_api_gateway_method" "any" {
  rest_api_id      = aws_api_gateway_rest_api.ops.id
  resource_id      = aws_api_gateway_resource.proxy.id
  http_method      = "ANY"
  authorization    = "NONE"
  api_key_required = true
  request_parameters = { "method.request.path.proxy" = true }
}

resource "aws_api_gateway_integration" "backend" {
  rest_api_id             = aws_api_gateway_rest_api.ops.id
  resource_id             = aws_api_gateway_resource.proxy.id
  http_method             = aws_api_gateway_method.any.http_method
  type                    = "HTTP_PROXY"
  integration_http_method = "ANY"
  uri                     = "${var.backend_url}/{proxy}"
  request_parameters      = { "integration.request.path.proxy" = "method.request.path.proxy" }
}

resource "aws_api_gateway_deployment" "d" {
  rest_api_id = aws_api_gateway_rest_api.ops.id
  triggers    = { redeploy = sha1(jsonencode([aws_api_gateway_method.any, aws_api_gateway_integration.backend])) }
  lifecycle { create_before_destroy = true }
}

resource "aws_api_gateway_stage" "v1" {
  rest_api_id   = aws_api_gateway_rest_api.ops.id
  deployment_id = aws_api_gateway_deployment.d.id
  stage_name    = "v1"
}

resource "aws_api_gateway_usage_plan" "plan" {
  name = "${var.name}-ops-plan"
  api_stages {
    api_id = aws_api_gateway_rest_api.ops.id
    stage  = aws_api_gateway_stage.v1.stage_name
  }
  throttle_settings {
    rate_limit  = var.rate_limit
    burst_limit = var.burst_limit
  }
  quota_settings {
    limit  = var.daily_quota
    period = "DAY"
  }
}

resource "aws_api_gateway_api_key" "ops" { name = "${var.name}-ops-key" }

resource "aws_api_gateway_usage_plan_key" "k" {
  key_id        = aws_api_gateway_api_key.ops.id
  key_type      = "API_KEY"
  usage_plan_id = aws_api_gateway_usage_plan.plan.id
}

output "invoke_url" { value = aws_api_gateway_stage.v1.invoke_url }
output "api_key_id" { value = aws_api_gateway_api_key.ops.id }
