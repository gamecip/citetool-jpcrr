#include "temporal-antialias.hpp"
#include <cmath>
#include <iostream>

framerate_reducer_temporalantialias::framerate_reducer_temporalantialias(double alpha, uint32_t n, uint32_t d)
{
	double lh = log(0.5);
	if(alpha < 0)
		factor = log(1-exp((1 - alpha) * lh))/lh;
	else
		factor = alpha + 1;
	newest = NULL;
	last_ts = 0;
	tdiv = 1000000000.0 * d / n;
}

framerate_reducer_temporalantialias::~framerate_reducer_temporalantialias()
{
	while(!queue.empty()) {
		delete queue.front().second;
		queue.pop_front();
	}
}

void framerate_reducer_temporalantialias::push(uint64_t ts, image_frame_rgbx& f)
{
	if(queue.empty() && newest) {
		delete newest;
		newest = NULL;
	}
	newest = &f;
	queue.push_back(std::make_pair(ts, newest));
}

image_frame_rgbx& framerate_reducer_temporalantialias::pull(uint64_t ts)
{
	image_frame_rgbx* result;
	if(queue.empty())
		return *new image_frame_rgbx(0, 0);	//No frame.

	result = new image_frame_rgbx(newest->get_width(), newest->get_height());
	size_t frames = queue.size();
	float* weights = new float[frames];
	float* tempframe = new float[4 * newest->get_width() * newest->get_height() + 1];

	compute_frame_weights(ts, weights);

	//Initialize the temporary frame.
	for(size_t i = 0; i < 4 * newest->get_width() * newest->get_height(); i++)
		tempframe[i] = 0;

	//Average the frames.
	size_t k = 0;
	for(std::list<std::pair<uint64_t, image_frame_rgbx*> >::iterator j = queue.begin(); j != queue.end();
		++j, ++k) {
		uint8_t* fpx = j->second->get_pixels();
		for(size_t i = 0; i < 4 * newest->get_width() * newest->get_height(); i++)
			tempframe[i] += weights[k] * fpx[i];
	}

	//Translate pixels back.
	uint8_t* px = result->get_pixels();
	for(size_t i = 0; i < 4 * newest->get_width() * newest->get_height(); i++) {
		if(tempframe[i] < 0)
			px[i] = 0;
		else if(tempframe[i] > 255)
			px[i] = 255;
		else
			px[i] = (uint8_t)tempframe[i];
	}

	//Delete all frames from queue except newest (and other temporaries).
	delete[] weights;
	delete[] tempframe;
	while(true) {
		if(queue.front().second == newest)
			break;
		delete queue.front().second;
		queue.pop_front();
	}
	last_ts = ts;
	return *result;
}

static double integrate_sensitivity(double fraction, double factor)
{
	if(fraction < 0.5)
		return 0.5 * pow(2 * fraction, factor);
	else
		return 1 - 0.5 * pow(2 - 2 * fraction, factor);
}

void framerate_reducer_temporalantialias::compute_frame_weights(uint64_t ts, float* weights)
{
	size_t k = 0;
	for(std::list<std::pair<uint64_t, image_frame_rgbx*> >::iterator j = queue.begin(); j != queue.end();
		++j, ++k) {
		uint64_t start_ts = j->first;
		//Note: The first frame needs to be processed as if its timestamp was last_ts.
		if(j == queue.begin())
			start_ts = last_ts;
		//The end timestamp is timestamp of next frame or ts if there's no next frame yet.
		std::list<std::pair<uint64_t, image_frame_rgbx*> >::iterator jn = j;
		jn++;
		uint64_t end_ts = ts;
		if(jn != queue.end())
			end_ts = jn->first;
		double start_fraction = 1 - (start_ts - last_ts) / tdiv;
		double end_fraction = 1 - (end_ts - last_ts) / tdiv;
		start_fraction = (start_fraction < 0) ? 0 : (start_fraction > 1) ? 1 : start_fraction;
		end_fraction = (end_fraction < 0) ? 0 : (end_fraction > 1) ? 1 : end_fraction;

		weights[k] = integrate_sensitivity(start_fraction, factor) - integrate_sensitivity(end_fraction,
			factor);
		//std::cerr << "Weight for subframe #" << k << ": " << weights[k] << "." << std::endl;
	}
}
