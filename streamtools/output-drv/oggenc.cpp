#include "output-drv.hpp"
#include <cstdio>
#include <stdexcept>
#include <string>
#include <sstream>

namespace
{
	class output_driver_oggenc : public output_driver
	{
	public:
		output_driver_oggenc(const std::string& _filename, const std::string& _options)
		{
			filename = _filename;
			options = _options;
			set_audio_callback<output_driver_oggenc>(*this, &output_driver_oggenc::audio_callback);
		}

		~output_driver_oggenc()
		{
			pclose(out);
		}

		void ready()
		{
			const audio_settings& a = get_audio_settings();

			std::stringstream commandline;
			commandline << "oggenc -r -R " << a.get_rate() << " ";
			commandline << expand_arguments_common(options, "--", "=");
			commandline << "-o " << filename << " -";
			std::string s = commandline.str();
			out = popen(s.c_str(), "w");
			if(!out) {
				std::stringstream str;
				str << "Can't run oggenc (" << s << ")";
				throw std::runtime_error(str.str());
			}
		}

		void audio_callback(short left, short right)
		{
			uint8_t rawdata[4];
			rawdata[1] = ((unsigned short)left >> 8) & 0xFF;
			rawdata[0] = ((unsigned short)left) & 0xFF;
			rawdata[3] = ((unsigned short)right >> 8) & 0xFF;
			rawdata[2] = ((unsigned short)right) & 0xFF;
			if(fwrite(rawdata, 1, 4, out) < 4)
				throw std::runtime_error("Error writing sample to oggenc");
		}
	private:
		FILE* out;
		std::string filename;
		std::string options;
	};

	class output_driver_oggenc_factory : output_driver_factory
	{
	public:
		output_driver_oggenc_factory()
			: output_driver_factory("oggenc")
		{
		}

		output_driver& make(const std::string& type, const std::string& name, const std::string& parameters)
		{
			return *new output_driver_oggenc(name, parameters);
		}
	} factory;
}