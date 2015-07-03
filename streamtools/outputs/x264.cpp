#include <cstring>
#include "outputs/internal.hpp"
#include "outputs/I420.hpp"
#include "outputs/argexpand.hpp"
#include <cstdio>
#include <stdexcept>
#include <vector>
#include <string>
#include <sstream>
#include <fcntl.h>

namespace
{
	class output_driver_x264 : public output_driver
	{
	public:
		output_driver_x264(const std::string& _filename, const std::string& _options, bool _newres)
		{
			filename = _filename;
			options = _options;
			set_video_callback(make_bound_method(*this, &output_driver_x264::video_callback));
			newres = _newres;
		}

		~output_driver_x264()
		{
			pclose(out);
		}

		void ready()
		{
			const video_settings& v = get_video_settings();
			framesize = 4 * v.get_width() * v.get_height();
			width = v.get_width();
			height = v.get_height();

			std::stringstream commandline;
			std::string executable = "x264";
			std::string x = expand_arguments_common(options, "--", " ", executable);
			commandline << executable << " ";
			if(v.get_rate_denum())
				commandline << "--fps " << v.get_rate_num() << "/" << v.get_rate_denum() << " ";
			commandline << " -o " << filename << " - --input-csp rgb ";
			commandline << "--input-res " << v.get_width() << "x" << v.get_height();
			commandline << " " << x;
			std::string s = commandline.str();
			std::cerr << s << std::endl;
			out = popen(s.c_str(), "w");
			if(!out) {
				std::stringstream str;
				str << "Can't run x264 (" << s << ")";
				throw std::runtime_error(str.str());
			}
#if defined(_WIN32) || defined(_WIN64)
			setmode(fileno(out), O_BINARY);
#endif
		}

		void video_callback(uint64_t timestamp, const uint8_t* raw_rgbx_data)
		{
			std::string tmp;
			tmp.resize(3 * width);
			size_t ptr = 0;
			for(size_t y = 0; y < height; y++) {
				size_t dptr = 0;
				for(size_t x = 0; x < width; x++) {
					tmp[dptr + 0] = raw_rgbx_data[ptr + 0];
					tmp[dptr + 1] = raw_rgbx_data[ptr + 1];
					tmp[dptr + 2] = raw_rgbx_data[ptr + 2];
					ptr += 4;
					dptr += 3;
				}
				fwrite(&tmp[0], 1, 3 * width, out);
			}
		}
	private:
		FILE* out;
		std::string filename;
		std::string options;
		size_t framesize;
		uint32_t width;
		uint32_t height;
		bool newres;
	};

	class output_driver_x264_factory : output_driver_factory
	{
	public:
		output_driver_x264_factory()
			: output_driver_factory("x264")
		{
		}

		output_driver& make(const std::string& type, const std::string& name, const std::string& parameters)
		{
			return *new output_driver_x264(name, parameters, true);
		}
	} factory1;

	class output_driver_x264n_factory : output_driver_factory
	{
	public:
		output_driver_x264n_factory()
			: output_driver_factory("x264n")
		{
		}

		output_driver& make(const std::string& type, const std::string& name, const std::string& parameters)
		{
			return *new output_driver_x264(name, parameters, true);
		}
	} factory2;

	class output_driver_x264o_factory : output_driver_factory
	{
	public:
		output_driver_x264o_factory()
			: output_driver_factory("x264o")
		{
		}

		output_driver& make(const std::string& type, const std::string& name, const std::string& parameters)
		{
			return *new output_driver_x264(name, parameters, false);
		}
	} factory3;

}
