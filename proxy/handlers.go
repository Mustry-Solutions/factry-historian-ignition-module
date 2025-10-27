package main

import (
	"encoding/json"
	"fmt"
	"log"
	"math/rand"
	"net/http"
)

// CollectorHandler handles POST requests to /collector
// @Summary Receive tag data (batch writes)
// @Description Accepts batch tag data from Ignition, logs to console, and discards
// @Tags Collector
// @Accept json
// @Produce json
// @Param request body CollectorRequest true "Batch tag samples"
// @Success 200 {object} CollectorResponse "Success response"
// @Failure 400 {object} CollectorResponse "Bad request"
// @Failure 405 {string} string "Method not allowed"
// @Router /collector [post]
func CollectorHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req CollectorRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		log.Printf("[COLLECTOR] Error decoding request: %v", err)
		respondJSON(w, http.StatusBadRequest, CollectorResponse{
			Success: false,
			Message: fmt.Sprintf("Invalid request body: %v", err),
		})
		return
	}

	// Print received samples to console
	log.Printf("[COLLECTOR] Received %d samples", len(req.Samples))
	for i, sample := range req.Samples {
		log.Printf("  [%d] TagPath: %s, Time: %s, Value: %v, Quality: %s (%d)",
			i+1,
			sample.TagPath,
			formatTimestamp(sample.Timestamp),
			sample.Value,
			qualityName(sample.Quality),
			sample.Quality,
		)
	}

	// Send success response
	respondJSON(w, http.StatusOK, CollectorResponse{
		Success: true,
		Message: "Samples received and logged",
		Count:   len(req.Samples),
	})
}

// ProviderHandler handles POST requests to /provider
// @Summary Query historical data
// @Description Returns randomly generated historical data for the requested tags
// @Tags Provider
// @Accept json
// @Produce json
// @Param request body ProviderRequest true "Query parameters"
// @Success 200 {object} ProviderResponse "Success response with data"
// @Failure 400 {object} ProviderResponse "Bad request"
// @Failure 405 {string} string "Method not allowed"
// @Router /provider [post]
func ProviderHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req ProviderRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		log.Printf("[PROVIDER] Error decoding request: %v", err)
		respondJSON(w, http.StatusBadRequest, ProviderResponse{
			Success: false,
			Message: fmt.Sprintf("Invalid request body: %v", err),
		})
		return
	}

	// Validate request
	if len(req.TagPaths) == 0 {
		respondJSON(w, http.StatusBadRequest, ProviderResponse{
			Success: false,
			Message: "No tag paths specified",
		})
		return
	}

	if req.StartTime >= req.EndTime {
		respondJSON(w, http.StatusBadRequest, ProviderResponse{
			Success: false,
			Message: "Start time must be before end time",
		})
		return
	}

	// Default maxPoints if not specified
	if req.MaxPoints == 0 {
		req.MaxPoints = 100
	}

	log.Printf("[PROVIDER] Query for %d tags from %s to %s (max %d points)",
		len(req.TagPaths),
		formatTimestamp(req.StartTime),
		formatTimestamp(req.EndTime),
		req.MaxPoints,
	)

	// Generate random data for each tag
	data := make(map[string][]TagSample)
	for _, tagPath := range req.TagPaths {
		log.Printf("[PROVIDER]   Generating data for tag: %s", tagPath)
		data[tagPath] = generateRandomSamples(tagPath, req.StartTime, req.EndTime, req.MaxPoints)
	}

	// Send response
	respondJSON(w, http.StatusOK, ProviderResponse{
		Success: true,
		Data:    data,
	})
}

// generateRandomSamples creates random historical data for a tag
func generateRandomSamples(tagPath string, startTime, endTime int64, maxPoints int) []TagSample {
	samples := make([]TagSample, 0, maxPoints)
	timeRange := endTime - startTime
	interval := timeRange / int64(maxPoints)

	for i := 0; i < maxPoints; i++ {
		timestamp := startTime + (int64(i) * interval)

		// Generate random value (simulating different data types)
		var value interface{}
		switch rand.Intn(3) {
		case 0:
			// Float value (e.g., temperature, pressure)
			value = rand.Float64() * 100
		case 1:
			// Integer value (e.g., count, status)
			value = rand.Intn(1000)
		case 2:
			// Boolean value (e.g., alarm state)
			value = rand.Intn(2) == 1
		}

		// Mostly good quality, occasionally bad or uncertain
		quality := QualityGood
		r := rand.Float32()
		if r < 0.05 {
			quality = QualityBad
		} else if r < 0.10 {
			quality = QualityUncertain
		}

		samples = append(samples, TagSample{
			TagPath:   tagPath,
			Timestamp: timestamp,
			Value:     value,
			Quality:   quality,
		})
	}

	return samples
}

// respondJSON is a helper function to send JSON responses
func respondJSON(w http.ResponseWriter, statusCode int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(statusCode)
	if err := json.NewEncoder(w).Encode(data); err != nil {
		log.Printf("Error encoding response: %v", err)
	}
}
