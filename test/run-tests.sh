#!/bin/bash

# MCP Test Runner Script
# This script runs comprehensive tests for the MCP interface

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOQUI_MCP_DIR="$(dirname "$SCRIPT_DIR")"

echo -e "${BLUE}🧪 MCP Test Suite${NC}"
echo -e "${BLUE}==================${NC}"
echo ""

# Check if Moqui MCP server is running
echo -e "${YELLOW}🔍 Checking if MCP server is running...${NC}"
if ! curl -s -u "john.sales:opencode" "http://localhost:8080/mcp" > /dev/null 2>&1; then
    echo -e "${RED}❌ MCP server is not running at http://localhost:8080/mcp${NC}"
    echo -e "${YELLOW}Please start the server first:${NC}"
    echo -e "${YELLOW}  cd moqui-mcp-2 && ../gradlew run --daemon > ../server.log 2>&1 &${NC}"
    exit 1
fi

echo -e "${GREEN}✅ MCP server is running${NC}"
echo ""

# Change to Moqui MCP directory
cd "$MOQUI_MCP_DIR"

# Build the project
echo -e "${YELLOW}🔨 Building MCP project...${NC}"
../gradlew build > /dev/null 2>&1
echo -e "${GREEN}✅ Build completed${NC}"
echo ""

# Run the test client
echo -e "${YELLOW}🚀 Running MCP Test Client...${NC}"
echo ""

# Run Groovy test client
groovy -cp "lib/*:build/libs/*:../framework/build/libs/*:../runtime/lib/*" \
    test/client/McpTestClient.groovy

echo ""
echo -e "${YELLOW}🛒 Running E-commerce Workflow Test...${NC}"
echo ""

# Run E-commerce workflow test
groovy -cp "lib/*:build/libs/*:../framework/build/libs/*:../runtime/lib/*" \
    test/workflows/EcommerceWorkflowTest.groovy

echo ""
echo -e "${BLUE}📋 All tests completed!${NC}"
echo -e "${YELLOW}Check the output above for detailed results.${NC}"