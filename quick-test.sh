#!/bin/bash

echo "=== Rate Limiter Quick Test ==="
echo ""

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Make requests
echo "4. Making 12 requests (capacity is 10)..."
success_count=0
blocked_count=0

for i in {1..12}; do
    response=$(curl -s -w "\n%{http_code}" http://localhost:8080/api/test 2>/dev/null)
    http_code=$(echo "$response" | tail -n1)
    
    if [ "$http_code" = "200" ]; then
        echo -e "  Request $i: ${GREEN}✓ Allowed (200)${NC}"
        ((success_count++))
    elif [ "$http_code" = "429" ]; then
        echo -e "  Request $i: ${RED}✗ Blocked (429)${NC}"
        ((blocked_count++))
    else
        echo -e "  Request $i: ${YELLOW}? Status ($http_code)${NC}"
    fi
done
echo ""

# Final status
echo "5. Final Rate Limit Status:"
final_status=$(curl -s http://localhost:8080/gateway/rate-limit/status)
echo "$final_status" | jq . 2>/dev/null || echo "$final_status"
final_tokens=$(echo "$final_status" | jq -r '.availableTokens' 2>/dev/null || echo "unknown")
echo ""

# Summary
echo "=== Test Summary ==="
echo "Successful requests: $success_count"
echo "Blocked requests: $blocked_count"
echo "Initial tokens: $initial_tokens"
echo "Final tokens: $final_tokens"
echo ""

if [ "$success_count" -eq 10 ] && [ "$blocked_count" -eq 2 ]; then
    echo -e "${GREEN}✓ Test PASSED! Rate limiting is working correctly.${NC}"
else
    echo -e "${YELLOW}⚠ Test results unexpected. Check configuration.${NC}"
fi

