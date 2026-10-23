terraform {
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.0" }
  }
}

# LocalStack: one provider alias per region, same module as envs/aws
provider "aws" {
  alias                       = "us"
  region                      = "us-east-1"
  access_key                  = "test"
  secret_key                  = "test"
  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true
  endpoints {
    sqs      = "http://localhost:4566"
    apigateway = "http://localhost:4566"
    dynamodb = "http://localhost:4566"
  }
}

provider "aws" {
  alias                       = "eu"
  region                      = "eu-west-1"
  access_key                  = "test"
  secret_key                  = "test"
  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true
  endpoints {
    sqs      = "http://localhost:4566"
    apigateway = "http://localhost:4566"
    dynamodb = "http://localhost:4566"
  }
}

provider "aws" {
  alias                       = "ca"
  region                      = "ca-central-1"
  access_key                  = "test"
  secret_key                  = "test"
  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true
  endpoints {
    sqs      = "http://localhost:4566"
    apigateway = "http://localhost:4566"
    dynamodb = "http://localhost:4566"
  }
}

module "us" {
  source    = "../../modules/queue_registry"
  providers = { aws = aws.us }
  name      = "us"
}

module "eu" {
  source    = "../../modules/queue_registry"
  providers = { aws = aws.eu }
  name      = "eu"
}

module "ca" {
  source    = "../../modules/queue_registry"
  providers = { aws = aws.ca }
  name      = "ca"
}

# Gateway in front of the ops API (auth by API key + throttling + quota). One per deployment, not per region.
module "gateway" {
  source      = "../../modules/api_gateway"
  providers   = { aws = aws.us }
  name        = "us"
  backend_url = "http://host.docker.internal:8080"
}

output "queues" {
  value = { us = module.us.queue_url, eu = module.eu.queue_url, ca = module.ca.queue_url }
}

output "gateway_url" { value = module.gateway.invoke_url }
output "gateway_key_id" { value = module.gateway.api_key_id }
