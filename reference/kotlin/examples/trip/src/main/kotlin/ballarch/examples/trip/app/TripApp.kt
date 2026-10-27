package ballarch.examples.trip.app

import ballarch.examples.trip.FlightBook
import ballarch.examples.trip.FlightReply
import ballarch.examples.trip.FlightVoid
import ballarch.examples.trip.HotelBook
import ballarch.examples.trip.HotelCancel
import ballarch.examples.trip.HotelReply
import ballarch.examples.trip.TripFlow
import ballarch.examples.trip.TripRef
import ballarch.runtime.Adapter
import ballarch.runtime.Composition

fun tripComposition(
    flightBook: Adapter<TripRef, FlightReply>,
    flightVoid: Adapter<TripRef, Unit>,
    hotelBook: Adapter<TripRef, HotelReply>,
    hotelCancel: Adapter<TripRef, Unit>,
    flow: TripFlow = TripFlow(),
): Composition =
    Composition()
        .ball(flow)
        .adapter(FlightBook, flightBook)
        .adapter(FlightVoid, flightVoid)
        .adapter(HotelBook, hotelBook)
        .adapter(HotelCancel, hotelCancel)
