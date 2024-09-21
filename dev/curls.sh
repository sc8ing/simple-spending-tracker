curl -D - localhost:8080/login -d 'username=abc&password=abc' -c cookies.txt
echo "Using cookies:"
cat cookies.txt
curl -X GET localhost:8080/api/transaction -c cookies.txt -b cookies.txt
