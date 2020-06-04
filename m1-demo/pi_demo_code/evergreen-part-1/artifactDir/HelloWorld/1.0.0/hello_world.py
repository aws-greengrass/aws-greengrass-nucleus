import sys
import datetime

message = f"Hello {sys.argv[1]}. Current time is: {str(datetime.datetime.now())}."
message += " Evergreen's local dev experience is great!"

# print to stdout
print(message)

# Append to file
with open('/tmp/Evergreen_HelloWorld.log', 'a') as f:
    print(message, file=f)