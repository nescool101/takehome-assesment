terraform {
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.0" }
  }
}

# Real AWS: same module as envs/local, no endpoint overrides.
provider "aws" {
  alias  = "us"
  region = "us-east-1"
}

provider "aws" {
  alias  = "eu"
  region = "eu-west-1"
}

provider "aws" {
  alias  = "ca"
  region = "ca-central-1"
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
  backend_url = "http://REPLACE-with-alb-dns"
}

