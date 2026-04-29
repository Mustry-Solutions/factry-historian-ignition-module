'''
Call these functions like this in the script console to see if the have any data:
# Define the tag paths
paths = [
	"[default]Factry/Realistic/Realistic0",
	"[default]Factry/Realistic/Realistic1"
]

# Raw data from the last 10 minutes
rawData = queryRawHistorian(paths, minutes=10, columnNames=["Realistic0", "Realistic1"])

# Aggregated averages from the last hour
aggData = queryAggregatedHistorian(paths, hours=1, aggregates=["Average", "Average"], columnNames=["Realistic0", "Realistic1"])
'''

def queryRawHistorian(paths, minutes=10, columnNames=None, returnFormat="WIDE", returnSize=500, includeBounds=False, excludeObservations=False):
	"""Query raw historical data points for the given tag paths.
	
	Args:
		paths: List of historian tag paths to query.
		minutes: Number of minutes to look back from now.
		columnNames: Optional list of friendly column aliases (must match length of paths).
		returnFormat: WIDE, TALL, or CALCULATION.
		returnSize: Maximum number of rows to return.
		includeBounds: Whether to include interpolated boundary points.
		excludeObservations: Whether to filter out periodic tag group samples.
	
	Returns:
		Dataset with raw data points.
	"""
	endTime = system.date.now()
	startTime = system.date.addMinutes(endTime, -minutes)
	
	rawData = system.historian.queryRawPoints(
		paths,
		startTime,
		endTime,
		columnNames=columnNames,
		returnFormat=returnFormat,
		returnSize=returnSize,
		includeBounds=includeBounds,
		excludeObservations=excludeObservations
	)
	
	# Print column headers and all rows for debugging
	colNames = [rawData.getColumnName(col) for col in range(rawData.columnCount)]
	print("Columns: {}".format(colNames))
	
	for row in range(rawData.rowCount):
		values = [rawData.getValueAt(row, col) for col in range(rawData.columnCount)]
		print("\t".join([str(v) for v in values]))
	
	return rawData


def queryAggregatedHistorian(paths, hours=1, aggregates=None, fillModes=None, columnNames=None, returnFormat="WIDE", returnSize=100, includeBounds=False, excludeObservations=False):
	"""Query aggregated historical data for the given tag paths.
	
	Args:
		paths: List of historian tag paths to query.
		hours: Number of hours to look back from now.
		aggregates: List of aggregate functions, one per path (e.g. ["Average", "Average"]).
		fillModes: List of fill modes, one per path (e.g. ["DERIVED", "DERIVED"]).
		columnNames: Optional list of friendly column aliases (must match length of paths).
		returnFormat: WIDE, TALL, or CALCULATION.
		returnSize: Maximum number of rows to return.
		includeBounds: Whether to include interpolated boundary points.
		excludeObservations: Whether to filter out periodic tag group samples.
	
	Returns:
		Dataset with aggregated data points.
	"""
	# Default aggregates and fillModes to one entry per path if not provided
	if aggregates is None:
		aggregates = ["Average"] * len(paths)
	if fillModes is None:
		fillModes = ["DERIVED"] * len(paths)
	
	endTime = system.date.now()
	startTime = system.date.addHours(endTime, -hours)
	
	aggData = system.historian.queryAggregatedPoints(
		paths,
		startTime,
		endTime,
		aggregates=aggregates,
		fillModes=fillModes,
		columnNames=columnNames,
		returnFormat=returnFormat,
		returnSize=returnSize,
		includeBounds=includeBounds,
		excludeObservations=excludeObservations
	)
	
	# Print column headers and all rows for debugging
	colNames = [aggData.getColumnName(col) for col in range(aggData.columnCount)]
	print("Columns: {}".format(colNames))
	
	for row in range(aggData.rowCount):
		values = [aggData.getValueAt(row, col) for col in range(aggData.columnCount)]
		print("\t".join([str(v) for v in values]))
	
	return aggData
	
	
