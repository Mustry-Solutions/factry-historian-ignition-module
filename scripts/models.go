package main

import "time"

// Quality codes following OPC UA standard
const (
	QualityGood      = 192 // 0xC0 - Good quality
	QualityBad       = 0   // 0x00 - Bad quality
	QualityUncertain = 64  // 0x40 - Uncertain quality
)

// TagSample represents a single data point for a tag
// This matches Ignition's historian format
type TagSample struct {
	TagPath   string      `json:"tagPath" example:"[default]Temperature"`   // Full tag path (e.g., "[default]Tag1")
	Timestamp int64       `json:"timestamp" example:"1704067200000"` // Unix timestamp in milliseconds
	Value     interface{} `json:"value" swaggertype:"number" example:"72.5"`     // Can be int, float, string, bool, etc.
	Quality   int         `json:"quality" example:"192"`   // OPC quality code (192 = Good)
}

// CollectorRequest represents a batch write request to the collector
type CollectorRequest struct {
	Samples []TagSample `json:"samples"`
}

// CollectorResponse represents the response from the collector
type CollectorResponse struct {
	Success bool   `json:"success" example:"true"`
	Message string `json:"message" example:"Samples received and logged"`
	Count   int    `json:"count" example:"2"` // Number of samples received
}

// ProviderRequest represents a query request to the provider
type ProviderRequest struct {
	TagPaths  []string `json:"tagPaths" example:"[default]Temperature,[default]Pressure"`            // List of tag paths to query
	StartTime int64    `json:"startTime" example:"1704067200000"`           // Unix timestamp in milliseconds
	EndTime   int64    `json:"endTime" example:"1704070800000"`             // Unix timestamp in milliseconds
	MaxPoints int      `json:"maxPoints,omitempty" example:"100"` // Optional: max data points to return per tag
}

// ProviderResponse represents the response from the provider
type ProviderResponse struct {
	Success bool                  `json:"success" example:"true"`
	Message string                `json:"message,omitempty" example:""`
	Data    map[string][]TagSample `json:"data"` // Map of tagPath -> samples
}

// Helper function to format timestamp
func formatTimestamp(ts int64) string {
	return time.UnixMilli(ts).Format(time.RFC3339)
}

// Helper function to get quality name
func qualityName(q int) string {
	switch q {
	case QualityGood:
		return "Good"
	case QualityBad:
		return "Bad"
	case QualityUncertain:
		return "Uncertain"
	default:
		return "Unknown"
	}
}
