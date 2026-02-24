package main

import (
	"log"
	"net"
	"net/http"
	"time"

	"github.com/gorilla/mux"
	"google.golang.org/grpc"

	pb "factry-historian-proxy/proto/historianpb"
)

const (
	HTTPPort = ":8111"
	GRPCPort = ":50051"
)

func main() {
	// Start gRPC server in a goroutine
	go startGRPCServer()

	// Create HTTP router
	r := mux.NewRouter()

	// Register endpoints
	r.HandleFunc("/collector", CollectorHandler).Methods("POST")
	r.HandleFunc("/provider", ProviderHandler).Methods("POST")

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
			"  gRPC :50051     - HistorianCollector.Store\n"))
	}).Methods("GET")

	// Configure server
	srv := &http.Server{
		Handler:      r,
		Addr:         HTTPPort,
		WriteTimeout: 15 * time.Second,
		ReadTimeout:  15 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	// Start server
	log.Printf("=================================================")
	log.Printf("Factry Historian Proxy Server")
	log.Printf("=================================================")
	log.Printf("HTTP server on port %s", HTTPPort)
	log.Printf("gRPC server on port %s", GRPCPort)
	log.Printf("Collector endpoint: http://localhost%s/collector", HTTPPort)
	log.Printf("Provider endpoint:  http://localhost%s/provider", HTTPPort)
	log.Printf("Health check:       http://localhost%s/health", HTTPPort)
	log.Printf("=================================================")

	if err := srv.ListenAndServe(); err != nil {
		log.Fatalf("HTTP server failed to start: %v", err)
	}
}

func startGRPCServer() {
	lis, err := net.Listen("tcp", GRPCPort)
	if err != nil {
		log.Fatalf("Failed to listen on %s: %v", GRPCPort, err)
	}

	s := grpc.NewServer()
	pb.RegisterHistorianCollectorServer(s, &historianServer{})

	log.Printf("gRPC server listening on %s", GRPCPort)
	if err := s.Serve(lis); err != nil {
		log.Fatalf("gRPC server failed: %v", err)
	}
}
