package main

import (
	"log"
	"net/http"
	"time"

	"github.com/gorilla/mux"
	httpSwagger "github.com/swaggo/http-swagger"

	_ "factry-historian-proxy/docs" // Import generated docs
)

// @title Factry Historian Proxy API
// @version 1.0
// @description Mock historian proxy server with collector and provider endpoints for testing the Factry Historian Ignition module.
// @termsOfService http://swagger.io/terms/

// @contact.name Factry Support
// @contact.url https://factry.io
// @contact.email support@factry.io

// @license.name MIT
// @license.url https://opensource.org/licenses/MIT

// @host localhost:8111
// @BasePath /
// @schemes http

const (
	Port = ":8111"
)

func main() {
	// Seed random number generator
	// Note: For Go 1.20+, rand is automatically seeded

	// Create router
	r := mux.NewRouter()

	// Register endpoints
	r.HandleFunc("/collector", CollectorHandler).Methods("POST")
	r.HandleFunc("/provider", ProviderHandler).Methods("POST")

	// Swagger UI
	r.PathPrefix("/swagger/").Handler(httpSwagger.WrapHandler)

	// Health check endpoint
	r.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("OK"))
	}).Methods("GET")

	// Root endpoint with info
	r.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain")
		w.Write([]byte("Factry Historian Proxy\n\n" +
			"Endpoints:\n" +
			"  POST /collector - Receive tag data (batch writes)\n" +
			"  POST /provider  - Query historical data\n" +
			"  GET  /health    - Health check\n" +
			"  GET  /swagger/  - Swagger UI documentation\n"))
	}).Methods("GET")

	// Configure server
	srv := &http.Server{
		Handler:      r,
		Addr:         Port,
		WriteTimeout: 15 * time.Second,
		ReadTimeout:  15 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	// Start server
	log.Printf("=================================================")
	log.Printf("Factry Historian Proxy Server")
	log.Printf("=================================================")
	log.Printf("Starting server on port %s", Port)
	log.Printf("Collector endpoint: http://localhost%s/collector", Port)
	log.Printf("Provider endpoint:  http://localhost%s/provider", Port)
	log.Printf("Swagger UI:         http://localhost%s/swagger/index.html", Port)
	log.Printf("=================================================")

	if err := srv.ListenAndServe(); err != nil {
		log.Fatalf("Server failed to start: %v", err)
	}
}
