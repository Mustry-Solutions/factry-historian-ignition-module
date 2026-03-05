package main

import (
	"log"
	"net"
	"net/http"
	"os"
	"time"

	"github.com/gorilla/mux"
	"google.golang.org/grpc"

	pb "factry-historian-proxy/proto/historianpb"
)

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func main() {
	httpPort := getEnv("HTTP_PORT", ":8111")
	grpcPort := getEnv("GRPC_PORT", ":9876")

	// Start gRPC server in a goroutine
	go startGRPCServer(grpcPort)

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
			"  gRPC " + grpcPort + "     - Historian service\n"))
	}).Methods("GET")

	// Configure server
	srv := &http.Server{
		Handler:      r,
		Addr:         httpPort,
		WriteTimeout: 15 * time.Second,
		ReadTimeout:  15 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	// Start server
	log.Printf("=================================================")
	log.Printf("Factry Historian Proxy Server")
	log.Printf("=================================================")
	log.Printf("HTTP server on port %s", httpPort)
	log.Printf("gRPC server on port %s", grpcPort)
	log.Printf("Collector endpoint: http://localhost%s/collector", httpPort)
	log.Printf("Provider endpoint:  http://localhost%s/provider", httpPort)
	log.Printf("Health check:       http://localhost%s/health", httpPort)
	log.Printf("=================================================")

	if err := srv.ListenAndServe(); err != nil {
		log.Fatalf("HTTP server failed to start: %v", err)
	}
}

func startGRPCServer(port string) {
	lis, err := net.Listen("tcp", port)
	if err != nil {
		log.Fatalf("Failed to listen on %s: %v", port, err)
	}

	s := grpc.NewServer()
	pb.RegisterHistorianServer(s, newHistorianServer())

	log.Printf("gRPC server listening on %s", port)
	if err := s.Serve(lis); err != nil {
		log.Fatalf("gRPC server failed: %v", err)
	}
}
