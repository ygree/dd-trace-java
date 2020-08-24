#!/usr/bin/env python

# Converts the Apache JMeter native JTL files into a CSV containing aggregaged values (min, max, avg, percentiles)
# Requires python-jtl and numpy modules

from jtl import create_parser
import numpy as np
import sys

replace_key_map = {'home':'1-Home', 'list vets':'2-List Vets', 'find owners landing':'3-Find Owners Landing', 'find all owners':'4-Find All Owners', 'find owner cole':'5-Find Owner Cole', 'find owner cole-0':'5-Find Owner Cole', 'find owner cole-1':'5-Find Owner Cole', 'display owner cole':'6-Display Owner Cole', 'find owner esco':'7-Find Owner Esco', 'find owner esco-0':'7-Find Owner Esco', 'find owner esco-1':'7-Find Owner Esco', 'display owner esco':'8-Display Owner Esco'}

input_file = sys.argv[1]

per_request = {}
parser = create_parser(input_file)
for sample in parser.itersamples():
if not sample.label.startswith('Warmup'):
key = replace_key_map[sample.label.lower()]
if not key in per_request:
per_request[key] = []

per_request[key].append(sample.latency_time.total_seconds())

req_map = dict()
for req in per_request:
data = per_request[req]
data_map = dict()
data_map['min'] = np.min(data)
data_map['max'] = np.max(data)
data_map['avg'] = np.average(data)
data_map['50'] = np.percentile(data, 50)
data_map['90'] = np.percentile(data, 90)
data_map['95'] = np.percentile(data, 95)
data_map['99'] = np.percentile(data, 99)
req_map[req] = data_map

print('Label,min,max,avg,P50,P90,P95,P99')
for key in sorted(req_map):
percentiles = req_map[key]
print('%s,%d,%d,%d,%d,%d,%d,%d' % (key, percentiles['min'] * 1000, percentiles['max'] * 1000, percentiles['avg'] * 1000, percentiles['50'] * 1000, percentiles['90'] * 1000, percentiles['95'] * 1000, percentiles['99'] * 1000))
