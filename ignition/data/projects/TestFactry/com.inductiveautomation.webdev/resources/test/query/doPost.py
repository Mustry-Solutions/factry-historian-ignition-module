def doPost(request, session):
	try:
		body = request['data']
		if hasattr(body, 'decode'):
			body = body.decode('utf-8')
		elif not isinstance(body, str):
			body = str(body)
		data = system.util.jsonDecode(body)

		paths = [str(p) for p in data["paths"]]
		start = system.date.fromMillis(long(data["startDate"]))
		end = system.date.fromMillis(long(data["endDate"]))

		kwargs = {
			"paths": paths,
			"startDate": start,
			"endDate": end,
		}

		if "aggregationMode" in data and data["aggregationMode"]:
			kwargs["aggregationMode"] = str(data["aggregationMode"])
		if "returnSize" in data and data["returnSize"]:
			kwargs["returnSize"] = int(data["returnSize"])

		dataset = system.tag.queryTagHistory(**kwargs)

		columns = [dataset.getColumnName(c) for c in range(dataset.getColumnCount())]
		rows = []
		for r in range(dataset.getRowCount()):
			row = {}
			for c in range(dataset.getColumnCount()):
				val = dataset.getValueAt(r, c)
				if val is None:
					row[columns[c]] = None
				elif hasattr(val, "getTime"):
					row[columns[c]] = val.getTime()
				elif isinstance(val, float) and val != val:
					row[columns[c]] = None
				else:
					row[columns[c]] = val
			rows.append(row)

		return {"json": {
			"success": True,
			"columns": columns,
			"rowCount": dataset.getRowCount(),
			"rows": rows
		}}

	except Exception, e:
		import traceback
		return {"json": {"success": False, "error": str(e), "trace": traceback.format_exc()}}
