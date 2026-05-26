# Required: funko_data.json

This directory needs the Kenny Chan Funko Pop dataset JSON file.

## How to get it

1. Go to: https://github.com/kennymkchan/funko-pop-data
2. Download the raw file: https://raw.githubusercontent.com/kennymkchan/funko-pop-data/master/data.json
3. Save it to THIS directory as: `funko_data.json`

## Why it's needed

This file (~23,000 Funko records) powers Layer 1 of the lookup waterfall.
Most UPC scans resolve here instantly, offline, with no API calls needed.

Without this file, every scan falls through to the Channel3 API (requires key + network).
The app will still work — it just won't have offline lookup capability.
