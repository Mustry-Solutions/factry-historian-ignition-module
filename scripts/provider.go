//go:build ignore

package main

import (
	"context"
	"crypto/tls"
	"flag"
	"fmt"
	"log"
	"math/rand"
	"os"
	"os/signal"
	"syscall"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/metadata"
	"google.golang.org/protobuf/types/known/timestamppb"
	"google.golang.org/protobuf/types/known/structpb"

	pb "factry-historian-proxy/proto/historianpb"
)

func main() {
	host := flag.String("host", "localhost", "Factry Historian gRPC host")
	port := flag.Int("port", 8001, "Factry Historian gRPC port")
	collectorUUID := flag.String("collector-uuid", "", "Collector UUID (required)")
	token := flag.String("token", "", "Bearer token for authentication (required)")
	measurement := flag.String("measurement", "test-tag", "Measurement name to create and send points for")
	interval := flag.Duration("interval", 1*time.Second, "Interval between points")
	flag.Parse()

	if *collectorUUID == "" || *token == "" {
		fmt.Fprintln(os.Stderr, "Error: --collector-uuid and --token are required")
		flag.Usage()
		os.Exit(1)
	}

	target := fmt.Sprintf("%s:%d", *host, *port)
	log.Printf("Connecting to Factry Historian at %s", target)

	tlsCreds := credentials.NewTLS(&tls.Config{InsecureSkipVerify: true})
	conn, err := grpc.NewClient(target, grpc.WithTransportCredentials(tlsCreds))
	if err != nil {
		log.Fatalf("Failed to connect: %v", err)
	}
	defer conn.Close()

	client := pb.NewHistorianClient(conn)

	// Attach collectoruuid and bearer token metadata to all calls
	ctx := metadata.AppendToOutgoingContext(context.Background(),
		"collectoruuid", *collectorUUID,
		"authorization", "Bearer "+*token,
	)

	// Step 1: Create measurement (auto-onboard)
	databaseUUID := "931e9996-1895-11f1-8943-ea818e02261d"
	log.Printf("Creating measurement '%s' with autoOnboard=true, database=%s", *measurement, databaseUUID)
	dataType := "number"
	settings, _ := structpb.NewStruct(map[string]interface{}{
		"databaseUUID": databaseUUID,
	})
	_, err = client.CreateMeasurements(ctx, &pb.CreateMeasurementsRequest{
		Measurements: []*pb.CreateMeasurement{
			{
				Name:        *measurement,
				DataType:    &dataType,
				AutoOnboard: true,
				Settings:    settings,
			},
		},
	})
	if err != nil {
		log.Fatalf("CreateMeasurements failed: %v", err)
	}
	log.Printf("CreateMeasurements succeeded")

	// Step 2: Get measurements to find the UUID
	resp, err := client.GetMeasurements(ctx, &pb.MeasurementRequest{
		CollectorUUID: *collectorUUID,
	})
	if err != nil {
		log.Fatalf("GetMeasurements failed: %v", err)
	}

	log.Printf("GetMeasurements returned %d measurements", len(resp.GetMeasurements()))
	var measurementUUID string
	for _, m := range resp.GetMeasurements() {
		log.Printf("  measurement: uuid=%s name=%s status=%s datatype=%s",
			m.GetUuid(), m.GetName(), m.GetStatus(), m.GetDatatype())
		if m.GetName() == *measurement && m.GetStatus() != "Deleted" && m.GetStatus() != "Discovered" {
			measurementUUID = m.GetUuid()
		}
	}
	// Fall back to Discovered if no active measurement found
	if measurementUUID == "" {
		for _, m := range resp.GetMeasurements() {
			if m.GetName() == *measurement && m.GetStatus() == "Discovered" {
				measurementUUID = m.GetUuid()
				break
			}
		}
	}
	if measurementUUID == "" {
		log.Fatalf("Could not find measurement UUID for '%s'", *measurement)
	}
	log.Printf("Measurement '%s' has UUID: %s", *measurement, measurementUUID)

	// Step 3: Send random points in a loop
	log.Printf("Sending random points every %s (Ctrl+C to stop)", *interval)

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)

	ticker := time.NewTicker(*interval)
	defer ticker.Stop()

	count := 0
	for {
		select {
		case <-sig:
			log.Printf("Stopped. Sent %d points total.", count)
			return
		case <-ticker.C:
			value := rand.Float64() * 100.0
			now := time.Now()

			val, _ := structpb.NewValue(value)
			point := &pb.Point{
				MeasurementUUID: measurementUUID,
				Timestamp:       timestamppb.New(now),
				Value:           val,
				Status:          "good",
			}

			_, err := client.CreatePoints(ctx, &pb.Points{
				Points: []*pb.Point{point},
			})

			count++
			if err != nil {
				log.Printf("[%d] ERROR sending point: %v", count, err)
			} else {
				log.Printf("[%d] Sent: measurement=%s  value=%.2f  time=%s  status=good",
					count, measurementUUID, value, now.UTC().Format("2006-01-02 15:04:05.000"))
			}
		}
	}
}
